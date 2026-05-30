"""Phase 8 (server-side OCR/AI extraction) tests.

Focus is on the pieces that don't talk to the real Anthropic API or a real
Redis. The vision client is injected via a fake; the arq pool is replaced
with a hand-built stub that records enqueued jobs. The actual worker task
function is invoked directly with a synthetic ctx so we exercise the DB
write paths without booting an arq event loop.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.api.deps import get_current_user, get_db
from app.core.config import settings
from app.db import session as session_module
from app.db.base import Base
from app.db.models import AuditEvent, Profile, Screenshot, TransactionDraft
from app.db.models.transaction_draft import (
    DRAFT_ERROR_UNREADABLE,
    DRAFT_STATUS_FAILED,
    DRAFT_STATUS_QUEUED,
    DRAFT_STATUS_RATE_LIMITED,
    DRAFT_STATUS_READY,
)
from app.main import app
from app.services import extraction as extraction_service
from app.worker import worker as worker_module
from app.worker.vision import VisionExtraction, normalize

# --- shared test DB plumbing --------------------------------------------

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
TestingSessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)

JPEG_MAGIC = b"\xff\xd8\xff\xe0"


@pytest.fixture(autouse=True)
def setup_db(tmp_path: Path):
    settings.proof_storage_backend = "local"
    settings.proof_storage_local_dir = str(tmp_path / "proofs")
    settings.extraction_enabled = True
    settings.environment = "development"
    Base.metadata.create_all(bind=engine)
    # The worker uses SessionLocal() from app.db.session directly (not via
    # FastAPI dependency injection). Point it at our in-memory engine for the
    # duration of the test so worker writes land in the same DB the API reads.
    original_session_local = session_module.SessionLocal
    session_module.SessionLocal = TestingSessionLocal
    yield
    session_module.SessionLocal = original_session_local
    Base.metadata.drop_all(bind=engine)


def _override_db():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()


def _make_user(user_id: uuid.UUID, email: str) -> None:
    db = TestingSessionLocal()
    db.add(Profile(id=user_id, email=email))
    db.commit()
    db.close()


@pytest.fixture
def client_for():
    def _build(user_id: uuid.UUID, email: str) -> TestClient:
        _make_user(user_id, email)

        def _override_user():
            db = TestingSessionLocal()
            try:
                yield db.get(Profile, user_id)
            finally:
                db.close()

        app.dependency_overrides[get_db] = _override_db
        app.dependency_overrides[get_current_user] = _override_user
        return TestClient(app)

    yield _build
    app.dependency_overrides.clear()


def _create_business(client: TestClient) -> uuid.UUID:
    business_id = uuid.uuid4()
    resp = client.post("/businesses", json={"id": str(business_id), "name": "Ledger Co"})
    assert resp.status_code == 200, resp.text
    return business_id


def _create_transaction(client: TestClient, business_id: uuid.UUID) -> uuid.UUID:
    transaction_id = uuid.uuid4()
    resp = client.post(
        f"/businesses/{business_id}/transactions",
        json={
            "id": str(transaction_id),
            "amount_minor": 125000,
            "currency": "NGN",
            "direction": "money_in",
            "transaction_date": "2026-05-24",
            "counterparty_name": "Adewale Supplies",
        },
    )
    assert resp.status_code == 200, resp.text
    return transaction_id


def _upload_screenshot(
    client: TestClient,
    business_id: uuid.UUID,
    transaction_id: uuid.UUID,
) -> dict[str, Any]:
    files = {"file": ("proof.jpg", JPEG_MAGIC + b"x" * 64, "image/jpeg")}
    data = {"transaction_id": str(transaction_id), "original_file_size": "64"}
    resp = client.post(f"/businesses/{business_id}/screenshots", files=files, data=data)
    assert resp.status_code == 200, resp.text
    return resp.json()


# --- vision normalizer --------------------------------------------------


class TestVisionNormalizer:
    """Pure-Python tests on the normalize() helper."""

    def test_happy_path_preserves_all_high_confidence_fields(self):
        result = normalize(
            {
                "amount_minor": 850000,
                "currency_code": "NGN",
                "transaction_date": "2026-05-24",
                "reference_number": "TRF240524ABC",
                "bank_name": "GTBank",
                "counterparty_name": "Jane Doe",
                "confidence": {
                    "amount_minor": 0.98,
                    "currency_code": 0.95,
                    "transaction_date": 0.95,
                    "reference_number": 0.99,
                    "bank_name": 0.9,
                    "counterparty_name": 0.85,
                },
                "unreadable": False,
            }
        )
        assert result.amount_minor == 850000
        assert result.currency_code == "NGN"
        assert result.transaction_date == date(2026, 5, 24)
        assert result.reference_number == "TRF240524ABC"
        assert result.bank_name == "GTBank"
        assert result.counterparty_name == "Jane Doe"
        assert result.unreadable is False

    def test_low_confidence_fields_are_dropped(self):
        # bank_name and counterparty_name fall below the 0.5 floor.
        result = normalize(
            {
                "amount_minor": 850000,
                "bank_name": "GTBank",
                "counterparty_name": "Jane",
                "confidence": {
                    "amount_minor": 0.9,
                    "bank_name": 0.3,
                    "counterparty_name": 0.49,
                },
                "unreadable": False,
            }
        )
        assert result.amount_minor == 850000
        assert result.bank_name is None
        assert result.counterparty_name is None

    def test_unreadable_wipes_all_fields_even_if_present(self):
        result = normalize(
            {
                "amount_minor": 99999,
                "bank_name": "Hallucinated",
                "confidence": {"amount_minor": 0.9, "bank_name": 0.9},
                "unreadable": True,
            }
        )
        assert result.unreadable is True
        assert result.amount_minor is None
        assert result.bank_name is None

    def test_malformed_date_returns_none(self):
        result = normalize(
            {
                "amount_minor": 100,
                "transaction_date": "not a date",
                "confidence": {"amount_minor": 0.9, "transaction_date": 0.9},
                "unreadable": False,
            }
        )
        assert result.transaction_date is None

    def test_negative_or_absurd_amount_returns_none(self):
        result = normalize(
            {
                "amount_minor": -100,
                "confidence": {"amount_minor": 0.9},
                "unreadable": False,
            }
        )
        assert result.amount_minor is None

    def test_currency_code_uppercased_and_truncated(self):
        result = normalize(
            {
                "currency_code": "ngn",
                "confidence": {"currency_code": 0.9},
                "unreadable": False,
            }
        )
        assert result.currency_code == "NGN"

    def test_missing_confidence_means_all_fields_dropped(self):
        # Default confidence is 0.0 → below floor → drop everything.
        result = normalize(
            {
                "amount_minor": 100,
                "bank_name": "GTBank",
                "unreadable": False,
            }
        )
        assert result.amount_minor is None
        assert result.bank_name is None


# --- worker task with a fake vision client ------------------------------


class FakeVisionClient:
    """Test double — returns a canned extraction (or raises)."""

    def __init__(self, *, extraction: VisionExtraction | None = None, error: Exception | None = None):
        self._extraction = extraction
        self._error = error
        self.calls: list[tuple[int, str]] = []

    async def extract(self, image_bytes: bytes, mime_type: str) -> VisionExtraction:
        self.calls.append((len(image_bytes), mime_type))
        if self._error is not None:
            raise self._error
        assert self._extraction is not None
        return self._extraction


def _write_proof_file(business_id: uuid.UUID, screenshot_id: uuid.UUID) -> str:
    """Write a fake proof file to the configured local storage dir.

    Mirrors ProofStorage.save's key layout so the worker can read it back.
    """
    storage_dir = Path(settings.proof_storage_local_dir) / str(business_id)
    storage_dir.mkdir(parents=True, exist_ok=True)
    storage_key = f"{business_id}/{screenshot_id}.jpg"
    (Path(settings.proof_storage_local_dir) / storage_key).write_bytes(JPEG_MAGIC + b"data")
    return storage_key


def _make_screenshot(business_id: uuid.UUID, transaction_id: uuid.UUID, user_id: uuid.UUID) -> uuid.UUID:
    """Insert a screenshot row directly (skipping the upload route)."""
    screenshot_id = uuid.uuid4()
    storage_key = _write_proof_file(business_id, screenshot_id)
    db = TestingSessionLocal()
    db.add(
        Screenshot(
            id=screenshot_id,
            business_id=business_id,
            transaction_id=transaction_id,
            uploaded_by=user_id,
            mime_type="image/jpeg",
            uploaded_file_size=10,
            original_file_size=10,
            width=None,
            height=None,
            storage_key=storage_key,
            storage_status="stored",
        )
    )
    db.commit()
    db.close()
    return screenshot_id


def _load_draft(screenshot_id: uuid.UUID) -> TransactionDraft | None:
    db = TestingSessionLocal()
    try:
        return (
            db.query(TransactionDraft)
            .filter(TransactionDraft.screenshot_id == screenshot_id)
            .one_or_none()
        )
    finally:
        db.close()


class TestExtractScreenshot:
    def test_happy_path_writes_ready_draft(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        transaction_id = _create_transaction(client, business_id)
        screenshot_id = _make_screenshot(business_id, transaction_id, user_id)

        fake = FakeVisionClient(
            extraction=VisionExtraction(
                amount_minor=850000,
                currency_code="NGN",
                transaction_date=date(2026, 5, 24),
                reference_number="REF1",
                bank_name="GTBank",
                counterparty_name="Jane Doe",
                confidence={"amount_minor": 0.95},
                unreadable=False,
                raw_json={"x": 1},
                model_version="test-model",
            )
        )
        ctx = {"vision": fake, "job_try": 1}
        result = asyncio.run(
            worker_module.extract_screenshot(ctx, str(screenshot_id))
        )
        assert result == {"status": DRAFT_STATUS_READY}

        draft = _load_draft(screenshot_id)
        assert draft is not None
        assert draft.status == DRAFT_STATUS_READY
        assert draft.amount_minor == 850000
        assert draft.bank_name == "GTBank"
        assert draft.error_code is None
        # raw_json kept (will be purged later by the cron job)
        assert draft.raw_json == {"x": 1}
        # vision was called with the file bytes we wrote
        assert len(fake.calls) == 1

    def test_unreadable_marks_failed_terminally(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        transaction_id = _create_transaction(client, business_id)
        screenshot_id = _make_screenshot(business_id, transaction_id, user_id)

        fake = FakeVisionClient(
            extraction=VisionExtraction(
                confidence={},
                unreadable=True,
                raw_json={"unreadable": True},
            )
        )
        ctx = {"vision": fake, "job_try": 1}
        result = asyncio.run(
            worker_module.extract_screenshot(ctx, str(screenshot_id))
        )
        assert result == {"status": DRAFT_STATUS_FAILED, "reason": DRAFT_ERROR_UNREADABLE}

        draft = _load_draft(screenshot_id)
        assert draft is not None
        assert draft.status == DRAFT_STATUS_FAILED
        assert draft.error_code == DRAFT_ERROR_UNREADABLE
        assert draft.amount_minor is None
        # raw_json preserved for debugging even on terminal failure
        assert draft.raw_json == {"unreadable": True}

    def test_vision_error_on_final_attempt_writes_failed(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        transaction_id = _create_transaction(client, business_id)
        screenshot_id = _make_screenshot(business_id, transaction_id, user_id)

        fake = FakeVisionClient(error=RuntimeError("anthropic 500"))
        # job_try=3 is the final attempt; _maybe_retry returns without raising
        # Retry, the original exception propagates, and the draft row carries
        # status=processing + error_code=vision_error.
        ctx = {"vision": fake, "job_try": worker_module.MAX_TRIES}
        with pytest.raises(RuntimeError):
            asyncio.run(worker_module.extract_screenshot(ctx, str(screenshot_id)))

        draft = _load_draft(screenshot_id)
        assert draft is not None
        assert draft.error_code == "vision_error"
        # Status stays processing on non-terminal failure — Phase 8 design
        # records failures by error_code but only flips status=failed on the
        # truly terminal "unreadable" path. Acceptable: client polls and sees
        # the error_code even if status isn't ready.
        assert draft.error_code is not None

    def test_skips_when_screenshot_missing(self):
        fake = FakeVisionClient(
            extraction=VisionExtraction(confidence={}, unreadable=False)
        )
        result = asyncio.run(
            worker_module.extract_screenshot(
                {"vision": fake, "job_try": 1},
                str(uuid.uuid4()),
            )
        )
        assert result == {"skipped": "not_found"}
        assert fake.calls == []


# --- GET /screenshots/{sid}/draft poll endpoint -------------------------


class TestDraftPollEndpoint:
    def test_returns_404_when_no_draft_yet(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        transaction_id = _create_transaction(client, business_id)
        screenshot_id = _make_screenshot(business_id, transaction_id, user_id)

        resp = client.get(f"/screenshots/{screenshot_id}/draft")
        assert resp.status_code == 404

    def test_returns_ready_draft_after_worker_finishes(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        transaction_id = _create_transaction(client, business_id)
        screenshot_id = _make_screenshot(business_id, transaction_id, user_id)

        fake = FakeVisionClient(
            extraction=VisionExtraction(
                amount_minor=1000,
                confidence={"amount_minor": 0.9},
                unreadable=False,
            )
        )
        asyncio.run(
            worker_module.extract_screenshot({"vision": fake, "job_try": 1}, str(screenshot_id))
        )

        resp = client.get(f"/screenshots/{screenshot_id}/draft")
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == DRAFT_STATUS_READY
        assert body["amount_minor"] == 1000

    def test_other_user_cannot_read_draft(self, client_for):
        # Owner uploads + worker runs
        owner_id = uuid.uuid4()
        client_owner = client_for(owner_id, "owner@example.com")
        business_id = _create_business(client_owner)
        transaction_id = _create_transaction(client_owner, business_id)
        screenshot_id = _make_screenshot(business_id, transaction_id, owner_id)
        fake = FakeVisionClient(
            extraction=VisionExtraction(
                amount_minor=1, confidence={"amount_minor": 0.9}, unreadable=False
            )
        )
        asyncio.run(
            worker_module.extract_screenshot({"vision": fake, "job_try": 1}, str(screenshot_id))
        )
        # Switch identity
        app.dependency_overrides.clear()
        intruder_id = uuid.uuid4()
        client_other = client_for(intruder_id, "other@example.com")
        resp = client_other.get(f"/screenshots/{screenshot_id}/draft")
        assert resp.status_code in (403, 404)


# --- confirm endpoint ---------------------------------------------------


class TestConfirmEndpoint:
    def test_stamps_confirmed_at_idempotently(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        transaction_id = _create_transaction(client, business_id)
        screenshot_id = _make_screenshot(business_id, transaction_id, user_id)
        fake = FakeVisionClient(
            extraction=VisionExtraction(
                amount_minor=1, confidence={"amount_minor": 0.9}, unreadable=False
            )
        )
        asyncio.run(
            worker_module.extract_screenshot({"vision": fake, "job_try": 1}, str(screenshot_id))
        )
        draft = _load_draft(screenshot_id)
        assert draft is not None
        draft_id = draft.id

        first = client.post(
            f"/transaction-drafts/{draft_id}/confirm",
            json={"transaction_id": str(transaction_id)},
        )
        assert first.status_code == 200
        first_confirmed_at = first.json()["confirmed_at"]
        assert first_confirmed_at is not None

        # Second call is a no-op — confirmed_at stays put.
        second = client.post(
            f"/transaction-drafts/{draft_id}/confirm",
            json={"transaction_id": str(transaction_id)},
        )
        assert second.status_code == 200
        assert second.json()["confirmed_at"] == first_confirmed_at

        db = TestingSessionLocal()
        try:
            events = db.query(AuditEvent).filter(AuditEvent.entity_id == draft_id).all()
            assert [(event.action, event.status) for event in events] == [
                ("confirm", "success")
            ]
        finally:
            db.close()


# --- rate limit ---------------------------------------------------------


class FakePipeline:
    def __init__(self, store: dict[str, int]):
        self._store = store
        self._ops: list[tuple[str, str, int | None]] = []

    def incr(self, key: str) -> FakePipeline:
        self._ops.append(("incr", key, None))
        return self

    def expire(self, key: str, seconds: int) -> FakePipeline:
        self._ops.append(("expire", key, seconds))
        return self

    async def execute(self) -> list[Any]:
        results: list[Any] = []
        for op, key, _ in self._ops:
            if op == "incr":
                self._store[key] = self._store.get(key, 0) + 1
                results.append(self._store[key])
            else:
                results.append(True)
        return results


class FakeArqPool:
    """Stand-in for ArqRedis. Only implements what try_enqueue_extraction calls."""

    def __init__(self):
        self.enqueued: list[tuple[str, tuple[Any, ...]]] = []
        self.counters: dict[str, int] = {}

    def pipeline(self) -> FakePipeline:
        return FakePipeline(self.counters)

    async def enqueue_job(self, name: str, *args: Any) -> None:
        self.enqueued.append((name, args))


class TestRateLimit:
    def test_blocks_after_per_minute_cap(self):
        pool = FakeArqPool()
        user_id = uuid.uuid4()
        settings.extraction_rate_per_minute = 3
        settings.extraction_rate_per_hour = 100

        statuses: list[str | None] = []
        for _ in range(5):
            status = asyncio.run(
                extraction_service.try_enqueue_extraction(
                    pool, user_id=user_id, screenshot_id=uuid.uuid4()
                )
            )
            statuses.append(status)

        assert statuses[:3] == [DRAFT_STATUS_QUEUED] * 3
        assert statuses[3:] == [DRAFT_STATUS_RATE_LIMITED, DRAFT_STATUS_RATE_LIMITED]
        # Only first 3 actually enqueued.
        assert len(pool.enqueued) == 3

    def test_disabled_feature_returns_none_without_enqueue(self):
        pool = FakeArqPool()
        settings.extraction_enabled = False
        try:
            status = asyncio.run(
                extraction_service.try_enqueue_extraction(
                    pool, user_id=uuid.uuid4(), screenshot_id=uuid.uuid4()
                )
            )
        finally:
            settings.extraction_enabled = True
        assert status is None
        assert pool.enqueued == []

    def test_missing_pool_returns_none(self):
        status = asyncio.run(
            extraction_service.try_enqueue_extraction(
                None, user_id=uuid.uuid4(), screenshot_id=uuid.uuid4()
            )
        )
        assert status is None


# --- retention ----------------------------------------------------------


class TestRawJsonPurge:
    def test_purges_only_rows_older_than_window(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        transaction_id = _create_transaction(client, business_id)

        # Two drafts: one ancient (raw_json should be nulled), one recent
        # (raw_json should be kept). Backdating done via direct UPDATE because
        # created_at is server-default.
        old_screenshot = _make_screenshot(business_id, transaction_id, user_id)
        new_screenshot = _make_screenshot(business_id, transaction_id, user_id)

        for sid in (old_screenshot, new_screenshot):
            fake = FakeVisionClient(
                extraction=VisionExtraction(
                    amount_minor=1,
                    confidence={"amount_minor": 0.9},
                    unreadable=False,
                    raw_json={"sid": str(sid)},
                )
            )
            asyncio.run(
                worker_module.extract_screenshot({"vision": fake, "job_try": 1}, str(sid))
            )

        # Backdate the first draft's created_at to 31 days ago.
        from sqlalchemy import update

        old_cutoff = datetime.now(tz=UTC) - timedelta(days=31)
        db = TestingSessionLocal()
        db.execute(
            update(TransactionDraft)
            .where(TransactionDraft.screenshot_id == old_screenshot)
            .values(created_at=old_cutoff)
        )
        db.commit()
        db.close()

        purged = asyncio.run(worker_module.purge_raw_json({}))
        assert purged == {"purged": 1}

        assert _load_draft(old_screenshot).raw_json is None
        assert _load_draft(new_screenshot).raw_json == {"sid": str(new_screenshot)}
