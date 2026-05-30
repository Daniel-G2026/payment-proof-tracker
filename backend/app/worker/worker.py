"""arq worker definition for Phase 8 extraction jobs.

Boot with `arq app.worker.worker.WorkerSettings`.

The worker keeps its own sync SQLAlchemy session (the rest of the backend is
sync, no point introducing an async stack here just for the worker). Sync DB
calls are wrapped in asyncio.to_thread so they don't block the async event
loop arq runs on.
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

from arq import cron
from arq.jobs import Job  # noqa: F401  # re-exported for tests
from sqlalchemy import update

from app.core.config import settings
from app.db import session as db_session
from app.db.models import Screenshot, TransactionDraft
from app.db.models.transaction_draft import (
    DRAFT_ERROR_PARSE,
    DRAFT_ERROR_UNREADABLE,
    DRAFT_ERROR_VISION,
    DRAFT_STATUS_FAILED,
    DRAFT_STATUS_PROCESSING,
    DRAFT_STATUS_READY,
)
from app.services.extraction import EXTRACT_TASK_NAME, build_arq_redis_settings
from app.services.proof_storage import ProofStorage
from app.worker.vision import (
    AnthropicVisionClient,
    VisionClient,
    VisionExtraction,
    VisionParseError,
)


def _session():
    """Resolve SessionLocal lazily so tests can patch app.db.session.SessionLocal
    without us holding a stale reference."""
    return db_session.SessionLocal()

log = logging.getLogger(__name__)

JOB_TIMEOUT_SECONDS = 120
MAX_TRIES = 3
RETRY_BASE_SECONDS = 10
PURGE_CRON_HOUR = 3  # UTC


async def startup(ctx: dict[str, Any]) -> None:
    """Build long-lived dependencies once when the worker process boots.

    Vision client construction is deferred + lazy: if ANTHROPIC_API_KEY isn't
    set yet, the worker still boots so the user can fix their .env without
    fighting a crash-loop. Jobs that arrive before the key is configured will
    fail cleanly via the normal `vision_error` path.
    """
    from app.core.observability import configure_logging, init_sentry

    configure_logging()
    init_sentry("worker")
    try:
        ctx["vision"] = _build_vision_client()
    except Exception as exc:  # noqa: BLE001
        log.warning(
            "Vision client unavailable at startup (%s). Worker is up but extract jobs "
            "will fail until the configuration is fixed.",
            exc,
        )
        ctx["vision"] = None


async def shutdown(ctx: dict[str, Any]) -> None:
    ctx.pop("vision", None)


def _build_vision_client() -> VisionClient:
    """Allow tests to override via the env-level flag. Default is the real client."""
    return AnthropicVisionClient()


async def extract_screenshot(ctx: dict[str, Any], screenshot_id_str: str) -> dict[str, Any]:
    """Run vision extraction for one screenshot.

    Idempotent: if a draft row already exists, we upsert into it. Re-runs on
    the same screenshot_id overwrite. Returns a small dict for arq job-result
    storage (Redis-backed); the durable result is the draft row in Postgres.
    """
    screenshot_id = uuid.UUID(screenshot_id_str)
    vision: VisionClient | None = ctx.get("vision")
    if vision is None:
        log.error(
            "extract_screenshot: vision client unavailable (likely missing "
            "ANTHROPIC_API_KEY); marking %s as failed.",
            screenshot_id,
        )
        screenshot = await asyncio.to_thread(_load_screenshot, screenshot_id)
        if screenshot is not None:
            await asyncio.to_thread(_mark_processing, screenshot)
            await asyncio.to_thread(
                _record_failure,
                screenshot,
                error_code=DRAFT_ERROR_VISION,
                error_message="Extraction is not configured on this server.",
                terminal=True,
            )
        return {"status": DRAFT_STATUS_FAILED, "reason": "vision_unconfigured"}

    screenshot = await asyncio.to_thread(_load_screenshot, screenshot_id)
    if screenshot is None:
        log.warning("extract_screenshot: %s not found, skipping", screenshot_id)
        return {"skipped": "not_found"}

    await asyncio.to_thread(_mark_processing, screenshot)

    image_bytes = await asyncio.to_thread(_read_image_bytes, screenshot.storage_key)

    try:
        extraction = await vision.extract(image_bytes, screenshot.mime_type)
    except VisionParseError as exc:
        await asyncio.to_thread(
            _record_failure,
            screenshot,
            error_code=DRAFT_ERROR_PARSE,
            error_message=str(exc),
        )
        # Retryable — model output drift is often transient.
        await _maybe_retry(ctx)
        raise
    except Exception as exc:  # noqa: BLE001 — we want a broad catch around the vendor SDK
        await asyncio.to_thread(
            _record_failure,
            screenshot,
            error_code=DRAFT_ERROR_VISION,
            error_message=_short(str(exc)),
        )
        await _maybe_retry(ctx)
        raise

    if extraction.unreadable:
        await asyncio.to_thread(
            _record_failure,
            screenshot,
            error_code=DRAFT_ERROR_UNREADABLE,
            error_message="Model reported image was not a payment proof.",
            terminal=True,
            extraction=extraction,
        )
        return {"status": DRAFT_STATUS_FAILED, "reason": DRAFT_ERROR_UNREADABLE}

    await asyncio.to_thread(_record_success, screenshot, extraction)
    return {"status": DRAFT_STATUS_READY}


async def _maybe_retry(ctx: dict[str, Any]) -> None:
    """Re-raise via arq.Retry if we have attempts remaining.

    Uses arq's built-in `Retry` to defer the next attempt. The job's
    `job_try` is 1-indexed.
    """
    from arq.worker import (
        Retry,  # local import: arq isn't imported at module load if tests patch it
    )

    job_try = ctx.get("job_try", 1)
    if job_try >= MAX_TRIES:
        # Final attempt; let the exception propagate so arq marks it complete
        # (the draft row is already in status=failed, so the client sees a
        # terminal answer either way).
        return
    raise Retry(defer=RETRY_BASE_SECONDS * (2 ** (job_try - 1)))


# --- DB helpers (sync; called via asyncio.to_thread) ---------------------


def _load_screenshot(screenshot_id: uuid.UUID) -> Screenshot | None:
    with _session() as db:
        row = db.get(Screenshot, screenshot_id)
        if row is None or row.deleted_at is not None:
            return None
        # Detach so the caller can read attributes after the session closes.
        db.expunge(row)
        return row


def _mark_processing(screenshot: Screenshot) -> None:
    """Upsert a draft row in status=processing.

    First worker pickup creates the row; later retries just bump status +
    attempts.
    """
    with _session() as db:
        draft = (
            db.query(TransactionDraft)
            .filter(TransactionDraft.screenshot_id == screenshot.id)
            .one_or_none()
        )
        if draft is None:
            draft = TransactionDraft(
                id=uuid.uuid4(),
                screenshot_id=screenshot.id,
                business_id=screenshot.business_id,
                status=DRAFT_STATUS_PROCESSING,
                attempts=1,
            )
            db.add(draft)
        else:
            draft.status = DRAFT_STATUS_PROCESSING
            draft.attempts = (draft.attempts or 0) + 1
            draft.error_code = None
            draft.error_message = None
        db.commit()


def _record_success(screenshot: Screenshot, extraction: VisionExtraction) -> None:
    with _session() as db:
        draft = (
            db.query(TransactionDraft)
            .filter(TransactionDraft.screenshot_id == screenshot.id)
            .one()
        )
        draft.status = DRAFT_STATUS_READY
        draft.amount_minor = extraction.amount_minor
        draft.currency_code = extraction.currency_code
        draft.transaction_date = extraction.transaction_date
        draft.reference_number = extraction.reference_number
        draft.bank_name = extraction.bank_name
        draft.counterparty_name = extraction.counterparty_name
        draft.confidence = extraction.confidence or None
        draft.raw_json = extraction.raw_json or None
        draft.model_version = extraction.model_version or None
        draft.error_code = None
        draft.error_message = None
        db.commit()


def _record_failure(
    screenshot: Screenshot,
    *,
    error_code: str,
    error_message: str,
    terminal: bool = False,
    extraction: VisionExtraction | None = None,
) -> None:
    """Write a failure to the draft row.

    `terminal=True` means we won't retry — used for unreadable images. The
    cleaned fields are left null. `extraction` (when supplied) is the raw
    response we still want to keep for debugging even on terminal failure.
    """
    with _session() as db:
        draft = (
            db.query(TransactionDraft)
            .filter(TransactionDraft.screenshot_id == screenshot.id)
            .one_or_none()
        )
        if draft is None:
            # _mark_processing didn't run (shouldn't happen, but be defensive).
            draft = TransactionDraft(
                id=uuid.uuid4(),
                screenshot_id=screenshot.id,
                business_id=screenshot.business_id,
                status=DRAFT_STATUS_FAILED,
                attempts=1,
            )
            db.add(draft)
        else:
            draft.status = DRAFT_STATUS_FAILED if terminal else draft.status
        draft.error_code = error_code
        draft.error_message = _short(error_message)
        if terminal:
            draft.status = DRAFT_STATUS_FAILED
        if extraction is not None:
            draft.raw_json = extraction.raw_json or None
            draft.model_version = extraction.model_version or None
        db.commit()


def _read_image_bytes(storage_key: str) -> bytes:
    return ProofStorage().read_bytes(storage_key)


def _short(message: str, limit: int = 500) -> str:
    return (message or "")[:limit]


# --- retention cron ------------------------------------------------------


async def purge_raw_json(ctx: dict[str, Any]) -> dict[str, int]:
    """Null out raw_json on drafts older than the retention window.

    Runs daily at 03:00 UTC (off-peak for the user base). The partial index
    on (created_at) WHERE raw_json IS NOT NULL keeps this cheap as the table
    grows — only un-redacted rows are scanned.
    """
    cutoff = datetime.now(tz=UTC) - timedelta(days=settings.extraction_raw_retention_days)
    return await asyncio.to_thread(_purge_raw_json_sync, cutoff)


def _purge_raw_json_sync(cutoff: datetime) -> dict[str, int]:
    with _session() as db:
        result = db.execute(
            update(TransactionDraft)
            .where(
                TransactionDraft.raw_json.is_not(None),
                TransactionDraft.created_at < cutoff,
            )
            .values(raw_json=None)
        )
        db.commit()
        # SQLAlchemy 2.x rowcount on UPDATE is reliable for Postgres.
        purged = result.rowcount or 0
        log.info("purge_raw_json purged %d rows older than %s", purged, cutoff.isoformat())
        return {"purged": purged}


# --- orphan screenshot cleanup (Phase 8.1) -------------------------------

ORPHAN_RETENTION_DAYS = 7
ORPHAN_PURGE_CRON_HOUR = 3
# 15-minute offset from purge_raw_json so the two daily crons don't fight for
# the connection pool or the worker's single-thread DB executor.
ORPHAN_PURGE_CRON_MINUTE = 15


async def purge_orphan_screenshots(ctx: dict[str, Any]) -> dict[str, int]:
    """Soft-delete orphan screenshots older than the retention window.

    An "orphan" is a screenshot with transaction_id IS NULL — uploaded by the
    Android form's eager-upload path but never linked to a saved transaction.
    Most arrive because the user attached a proof and then bailed out of the
    form.

    Storage bytes are deleted best-effort (logged on failure but don't roll
    back the soft-delete). The Phase 8 transaction_drafts FK cascades on
    Screenshot delete, but since we soft-delete (set deleted_at) instead of
    hard-deleting, drafts hang around with the orphaned screenshot row. They
    eventually get their raw_json scrubbed by purge_raw_json. This is fine —
    a soft-deleted screenshot is invisible to all client queries.
    """
    cutoff = datetime.now(tz=UTC) - timedelta(days=ORPHAN_RETENTION_DAYS)
    return await asyncio.to_thread(_purge_orphan_screenshots_sync, cutoff)


def _purge_orphan_screenshots_sync(cutoff: datetime) -> dict[str, int]:
    with _session() as db:
        # Snapshot the candidates so we can delete their storage files after
        # the soft-delete commits. The partial index on (created_at) WHERE
        # transaction_id IS NULL keeps this scan cheap.
        candidates = (
            db.query(Screenshot.id, Screenshot.storage_key)
            .filter(
                Screenshot.transaction_id.is_(None),
                Screenshot.deleted_at.is_(None),
                Screenshot.created_at < cutoff,
            )
            .all()
        )
        if not candidates:
            return {"purged": 0}
        now = datetime.now(tz=UTC)
        ids = [row.id for row in candidates]
        db.execute(
            update(Screenshot)
            .where(Screenshot.id.in_(ids))
            .values(deleted_at=now)
        )
        db.commit()
    storage = ProofStorage()
    storage_failures = 0
    for row in candidates:
        try:
            storage.delete(row.storage_key)
        except Exception:  # noqa: BLE001 — best-effort; soft-delete already won
            storage_failures += 1
            log.exception("purge_orphan_screenshots: failed to delete %s", row.storage_key)
    log.info(
        "purge_orphan_screenshots soft-deleted %d orphans (storage failures: %d)",
        len(candidates),
        storage_failures,
    )
    return {"purged": len(candidates)}


# --- WorkerSettings ------------------------------------------------------


class WorkerSettings:
    functions = [extract_screenshot, purge_raw_json, purge_orphan_screenshots]
    cron_jobs = [
        cron(purge_raw_json, hour={PURGE_CRON_HOUR}, minute={0}, run_at_startup=False),
        cron(
            purge_orphan_screenshots,
            hour={ORPHAN_PURGE_CRON_HOUR},
            minute={ORPHAN_PURGE_CRON_MINUTE},
            run_at_startup=False,
        ),
    ]
    on_startup = startup
    on_shutdown = shutdown
    max_jobs = 4
    job_timeout = JOB_TIMEOUT_SECONDS
    max_tries = MAX_TRIES
    # Override with a custom RedisSettings at boot.
    redis_settings = build_arq_redis_settings()


# Re-export the task name so callers don't need a separate import.
__all__ = [
    "WorkerSettings",
    "extract_screenshot",
    "purge_raw_json",
    "purge_orphan_screenshots",
    "EXTRACT_TASK_NAME",
]
