"""Server-side OCR/AI extraction enqueue helper + per-user rate limiter.

The upload route calls `try_enqueue_extraction` after the screenshot row is
committed. Rate limiting is best-effort; if it can't talk to Redis we fail
*open* (let the job through) rather than blocking uploads on a Redis blip.

Token-bucket implementation: two Redis counters per user (per-minute and
per-hour), each with a TTL matching the window. INCR + EXPIRE is good enough
here — we don't need sliding-window precision for "stop a runaway client."
"""

from __future__ import annotations

import logging
import uuid
from typing import Any

from arq.connections import ArqRedis

from app.core.config import settings
from app.db.models.transaction_draft import DRAFT_STATUS_QUEUED, DRAFT_STATUS_RATE_LIMITED

log = logging.getLogger(__name__)

EXTRACT_TASK_NAME = "extract_screenshot"


async def try_enqueue_extraction(
    pool: ArqRedis | None,
    *,
    user_id: uuid.UUID,
    screenshot_id: uuid.UUID,
) -> str | None:
    """Enqueue an extraction job if we're allowed to.

    Returns the draft_status to surface on the upload response:
    - "queued" — job enqueued, worker will pick it up.
    - "rate_limited" — over the per-user quota, no job enqueued.
    - None — feature flag off or pool unavailable; client should treat as
      "no draft for this upload, type fields manually."
    """
    if not settings.extraction_enabled:
        return None
    if pool is None:
        # Pool wasn't created at startup (Redis unreachable in dev). Don't
        # crash the upload; just skip extraction.
        log.warning("Extraction skipped: no arq redis pool available")
        return None

    allowed = await _consume_rate_token(pool, user_id)
    if not allowed:
        return DRAFT_STATUS_RATE_LIMITED

    try:
        await pool.enqueue_job(EXTRACT_TASK_NAME, str(screenshot_id))
    except Exception:  # pragma: no cover — defensive: never break upload on enqueue failure
        log.exception("Failed to enqueue extraction for screenshot %s", screenshot_id)
        return None
    return DRAFT_STATUS_QUEUED


async def _consume_rate_token(pool: ArqRedis, user_id: uuid.UUID) -> bool:
    """Return True if the user is under both per-minute and per-hour caps.

    Increments both counters even on rejection so a hammering client doesn't
    get a free pass at the top of the next window.
    """
    minute_key = f"extract:rl:m:{user_id}"
    hour_key = f"extract:rl:h:{user_id}"
    try:
        # arq's underlying redis client exposes the async commands directly.
        pipe = pool.pipeline()
        pipe.incr(minute_key)
        pipe.expire(minute_key, 60)
        pipe.incr(hour_key)
        pipe.expire(hour_key, 3600)
        minute_count, _, hour_count, _ = await pipe.execute()
    except Exception:
        # Redis hiccup: fail open. The worker has its own backpressure
        # (max_jobs=4) so we can't run away too far.
        log.exception("Rate-limit Redis call failed; allowing request")
        return True

    if minute_count > settings.extraction_rate_per_minute:
        return False
    if hour_count > settings.extraction_rate_per_hour:
        return False
    return True


def build_arq_redis_settings() -> Any:
    """Translate our redis_url config into arq's RedisSettings.

    Kept here so main.py doesn't need to know arq's internal naming.
    """
    from arq.connections import RedisSettings

    return RedisSettings.from_dsn(settings.redis_url)
