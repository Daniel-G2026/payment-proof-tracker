"""Phase 13 Cut 6 — offer-letter AI extraction tests.

Same shape as test_phase8_extraction.py: a fake vision client, the worker task
invoked directly with a synthetic ctx, and the poll endpoint via TestClient.
Asserts the contract's worked examples A–D (docs/phase-13-offer-letter-contract).
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, update
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.api.deps import get_current_user, get_db
from app.core.config import settings
from app.db import session as session_module
from app.db.base import Base
from app.db.models import Profile, ScheduleDraft, Screenshot, TransactionDraft
from app.db.models.schedule_draft import (
    SCHEDULE_DRAFT_ERROR_UNREADABLE,
    SCHEDULE_DRAFT_STATUS_FAILED,
    SCHEDULE_DRAFT_STATUS_READY,
)
from app.db.models.screenshot import (
    DOCUMENT_TYPE_OFFER_LETTER,
    DOCUMENT_TYPE_PAYMENT_PROOF,
)
from app.main import app
from app.worker import worker as worker_module
from app.worker.vision import OfferLetterExtraction, normalize_offer_letter

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
    resp = client.post("/businesses", json={"id": str(business_id), "name": "Estate Co"})
    assert resp.status_code == 200, resp.text
    return business_id


def _write_proof_file(business_id: uuid.UUID, screenshot_id: uuid.UUID) -> str:
    storage_dir = Path(settings.proof_storage_local_dir) / str(business_id)
    storage_dir.mkdir(parents=True, exist_ok=True)
    storage_key = f"{business_id}/{screenshot_id}.jpg"
    (Path(settings.proof_storage_local_dir) / storage_key).write_bytes(JPEG_MAGIC + b"data")
    return storage_key


def _make_screenshot(
    business_id: uuid.UUID,
    user_id: uuid.UUID,
    *,
    document_type: str = DOCUMENT_TYPE_OFFER_LETTER,
) -> uuid.UUID:
    screenshot_id = uuid.uuid4()
    storage_key = _write_proof_file(business_id, screenshot_id)
    db = TestingSessionLocal()
    db.add(
        Screenshot(
            id=screenshot_id,
            business_id=business_id,
            transaction_id=None,
            uploaded_by=user_id,
            mime_type="image/jpeg",
            uploaded_file_size=10,
            original_file_size=10,
            storage_key=storage_key,
            storage_status="stored",
            document_type=document_type,
        )
    )
    db.commit()
    db.close()
    return screenshot_id


def _load_schedule_draft(screenshot_id: uuid.UUID) -> ScheduleDraft | None:
    db = TestingSessionLocal()
    try:
        return (
            db.query(ScheduleDraft)
            .filter(ScheduleDraft.screenshot_id == screenshot_id)
            .one_or_none()
        )
    finally:
        db.close()


# --- normalizer (contract examples A–D) ----------------------------------


def _monthly_rows(start: tuple[int, int, int], amount: int, n: int) -> list[dict]:
    rows = []
    y, m, d = start
    for i in range(n):
        mm = m + i
        yy = y + (mm - 1) // 12
        mm = (mm - 1) % 12 + 1
        rows.append({"sequence": i, "amount_minor": amount, "due_date": f"{yy:04d}-{mm:02d}-{d:02d}"})
    return rows


class TestOfferLetterNormalizer:
    def test_example_a_clean_plan_kept_and_resequenced(self):
        # deposit + 10 monthly = 11 rows; total matches sum.
        installments = [{"sequence": 0, "amount_minor": 200000000, "due_date": "2026-07-01"}]
        installments += _monthly_rows((2026, 8, 1), 100000000, 10)
        result = normalize_offer_letter(
            {
                "buyer_name": "Ada Obi",
                "total_minor": 1200000000,
                "currency_code": "ngn",
                "installments": installments,
                "confidence": {
                    "buyer_name": 0.95,
                    "total_minor": 0.95,
                    "currency_code": 0.95,
                    "installments": 0.92,
                },
                "unreadable": False,
            }
        )
        assert result.buyer_name == "Ada Obi"
        assert result.total_minor == 1200000000
        assert result.currency_code == "NGN"
        assert result.installments is not None
        assert len(result.installments) == 11
        assert [r["sequence"] for r in result.installments] == list(range(11))
        assert sum(r["amount_minor"] for r in result.installments) == 1200000000

    def test_example_b_does_not_add_up_is_kept_unaltered(self):
        # total says 12,000,000 but rows sum to 11,000,000 — normalizer must
        # NOT reconcile; both are kept for the UI to flag.
        result = normalize_offer_letter(
            {
                "total_minor": 1200000000,
                "installments": [
                    {"sequence": 0, "amount_minor": 600000000, "due_date": "2026-07-01"},
                    {"sequence": 1, "amount_minor": 500000000, "due_date": "2026-08-01"},
                ],
                "confidence": {"total_minor": 0.9, "installments": 0.9},
                "unreadable": False,
            }
        )
        assert result.total_minor == 1200000000
        assert result.installments is not None
        assert sum(r["amount_minor"] for r in result.installments) == 1100000000

    def test_example_c_unreadable_wipes_everything(self):
        result = normalize_offer_letter(
            {
                "buyer_name": "X",
                "total_minor": 1,
                "installments": [{"amount_minor": 1, "due_date": "2026-01-01"}],
                "confidence": {"buyer_name": 0.9, "installments": 0.9},
                "unreadable": True,
            }
        )
        assert result.unreadable is True
        assert result.buyer_name is None
        assert result.total_minor is None
        assert result.installments is None

    def test_example_d_low_confidence_buyer_dropped_rows_kept(self):
        result = normalize_offer_letter(
            {
                "buyer_name": "Smudged",
                "installments": [{"sequence": 0, "amount_minor": 100, "due_date": "2026-01-01"}],
                "confidence": {"buyer_name": 0.3, "installments": 0.9},
                "unreadable": False,
            }
        )
        assert result.buyer_name is None
        assert result.installments is not None
        assert len(result.installments) == 1

    def test_low_confidence_installments_array_dropped(self):
        result = normalize_offer_letter(
            {
                "installments": [{"amount_minor": 100, "due_date": "2026-01-01"}],
                "confidence": {"installments": 0.4},
                "unreadable": False,
            }
        )
        assert result.installments is None

    def test_malformed_rows_dropped_and_resequenced_by_due_date(self):
        result = normalize_offer_letter(
            {
                "installments": [
                    {"sequence": 0, "amount_minor": 300, "due_date": "2026-03-01"},
                    {"sequence": 1, "amount_minor": -5, "due_date": "2026-02-01"},  # bad amount
                    {"sequence": 2, "amount_minor": 100, "due_date": "not-a-date"},  # bad date
                    {"sequence": 3, "amount_minor": 200, "due_date": "2026-01-01"},
                ],
                "confidence": {"installments": 0.9},
                "unreadable": False,
            }
        )
        assert result.installments is not None
        # Two valid rows survive, re-sorted by due_date and re-sequenced 0,1.
        assert [r["due_date"] for r in result.installments] == ["2026-01-01", "2026-03-01"]
        assert [r["sequence"] for r in result.installments] == [0, 1]

    def test_empty_installments_after_cleaning_becomes_null(self):
        result = normalize_offer_letter(
            {
                "installments": [{"amount_minor": 0, "due_date": "2026-01-01"}],
                "confidence": {"installments": 0.9},
                "unreadable": False,
            }
        )
        assert result.installments is None


# --- worker offer-letter path -------------------------------------------


class FakeOfferLetterVisionClient:
    def __init__(self, *, extraction: OfferLetterExtraction | None = None, error: Exception | None = None):
        self._extraction = extraction
        self._error = error
        self.calls = 0

    async def extract_offer_letter(self, image_bytes: bytes, mime_type: str) -> OfferLetterExtraction:
        self.calls += 1
        if self._error is not None:
            raise self._error
        assert self._extraction is not None
        return self._extraction

    async def extract(self, image_bytes: bytes, mime_type: str):  # pragma: no cover
        raise AssertionError("payment-proof path should not be used for offer letters")


class TestExtractOfferLetter:
    def test_happy_path_writes_ready_schedule_draft(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        screenshot_id = _make_screenshot(business_id, user_id)

        fake = FakeOfferLetterVisionClient(
            extraction=OfferLetterExtraction(
                buyer_name="Ada Obi",
                total_minor=300,
                currency_code="NGN",
                installments=[
                    {"sequence": 0, "amount_minor": 100, "due_date": "2026-07-01"},
                    {"sequence": 1, "amount_minor": 200, "due_date": "2026-08-01"},
                ],
                confidence={"installments": 0.9},
                unreadable=False,
                raw_json={"x": 1},
                model_version="test-model",
            )
        )
        result = asyncio.run(
            worker_module.extract_screenshot({"vision": fake, "job_try": 1}, str(screenshot_id))
        )
        assert result == {"status": SCHEDULE_DRAFT_STATUS_READY}

        draft = _load_schedule_draft(screenshot_id)
        assert draft is not None
        assert draft.status == SCHEDULE_DRAFT_STATUS_READY
        assert draft.buyer_name == "Ada Obi"
        assert draft.total_minor == 300
        assert len(draft.installments) == 2
        assert draft.raw_json == {"x": 1}
        assert fake.calls == 1
        # No transaction draft was created for an offer-letter screenshot.
        db = TestingSessionLocal()
        assert (
            db.query(TransactionDraft).filter(
                TransactionDraft.screenshot_id == screenshot_id
            ).one_or_none()
            is None
        )
        db.close()

    def test_unreadable_marks_failed_terminally(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        screenshot_id = _make_screenshot(business_id, user_id)

        fake = FakeOfferLetterVisionClient(
            extraction=OfferLetterExtraction(
                confidence={}, unreadable=True, raw_json={"unreadable": True}
            )
        )
        result = asyncio.run(
            worker_module.extract_screenshot({"vision": fake, "job_try": 1}, str(screenshot_id))
        )
        assert result == {"status": SCHEDULE_DRAFT_STATUS_FAILED, "reason": SCHEDULE_DRAFT_ERROR_UNREADABLE}
        draft = _load_schedule_draft(screenshot_id)
        assert draft.status == SCHEDULE_DRAFT_STATUS_FAILED
        assert draft.error_code == SCHEDULE_DRAFT_ERROR_UNREADABLE
        assert draft.installments is None

    def test_payment_proof_screenshot_does_not_create_schedule_draft(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        screenshot_id = _make_screenshot(
            business_id, user_id, document_type=DOCUMENT_TYPE_PAYMENT_PROOF
        )

        # extract_offer_letter must never be called for a payment proof; this
        # fake would raise from extract(), proving dispatch routed away.
        from app.worker.vision import VisionExtraction

        class ProofFake:
            async def extract(self, image_bytes, mime_type):
                return VisionExtraction(
                    amount_minor=1, confidence={"amount_minor": 0.9}, unreadable=False
                )

            async def extract_offer_letter(self, image_bytes, mime_type):  # pragma: no cover
                raise AssertionError("offer-letter path used for a payment proof")

        asyncio.run(
            worker_module.extract_screenshot({"vision": ProofFake(), "job_try": 1}, str(screenshot_id))
        )
        assert _load_schedule_draft(screenshot_id) is None

    def test_skips_when_screenshot_missing(self):
        fake = FakeOfferLetterVisionClient(
            extraction=OfferLetterExtraction(confidence={}, unreadable=False)
        )
        # Missing screenshot → document_type lookup returns None → payment-proof
        # path runs and reports not_found (no schedule draft attempted).
        result = asyncio.run(
            worker_module.extract_screenshot({"vision": fake, "job_try": 1}, str(uuid.uuid4()))
        )
        assert result == {"skipped": "not_found"}


# --- poll endpoint -------------------------------------------------------


class TestScheduleDraftPollEndpoint:
    def test_returns_404_when_no_draft_yet(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        screenshot_id = _make_screenshot(business_id, user_id)
        resp = client.get(f"/screenshots/{screenshot_id}/schedule-draft")
        assert resp.status_code == 404

    def test_returns_ready_draft_after_worker_finishes(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        screenshot_id = _make_screenshot(business_id, user_id)
        fake = FakeOfferLetterVisionClient(
            extraction=OfferLetterExtraction(
                buyer_name="Ada",
                installments=[{"sequence": 0, "amount_minor": 100, "due_date": "2026-01-01"}],
                confidence={"installments": 0.9},
                unreadable=False,
            )
        )
        asyncio.run(
            worker_module.extract_screenshot({"vision": fake, "job_try": 1}, str(screenshot_id))
        )
        resp = client.get(f"/screenshots/{screenshot_id}/schedule-draft")
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == SCHEDULE_DRAFT_STATUS_READY
        assert body["buyer_name"] == "Ada"
        assert len(body["installments"]) == 1

    def test_other_user_cannot_read_draft(self, client_for):
        owner_id = uuid.uuid4()
        client_owner = client_for(owner_id, "owner@example.com")
        business_id = _create_business(client_owner)
        screenshot_id = _make_screenshot(business_id, owner_id)
        fake = FakeOfferLetterVisionClient(
            extraction=OfferLetterExtraction(
                installments=[{"sequence": 0, "amount_minor": 1, "due_date": "2026-01-01"}],
                confidence={"installments": 0.9},
                unreadable=False,
            )
        )
        asyncio.run(
            worker_module.extract_screenshot({"vision": fake, "job_try": 1}, str(screenshot_id))
        )
        app.dependency_overrides.clear()
        client_other = client_for(uuid.uuid4(), "other@example.com")
        resp = client_other.get(f"/screenshots/{screenshot_id}/schedule-draft")
        assert resp.status_code in (403, 404)


# --- upload document_type + retention -----------------------------------


class TestUploadDocumentType:
    def test_upload_rejects_unknown_document_type(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        files = {"file": ("doc.jpg", JPEG_MAGIC + b"x" * 64, "image/jpeg")}
        data = {"original_file_size": "64", "document_type": "totally_bogus"}
        resp = client.post(f"/businesses/{business_id}/screenshots", files=files, data=data)
        assert resp.status_code == 422

    def test_upload_defaults_to_payment_proof(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        files = {"file": ("doc.jpg", JPEG_MAGIC + b"x" * 64, "image/jpeg")}
        resp = client.post(
            f"/businesses/{business_id}/screenshots",
            files=files,
            data={"original_file_size": "64"},
        )
        assert resp.status_code == 200
        sid = uuid.UUID(resp.json()["id"])
        db = TestingSessionLocal()
        try:
            assert db.get(Screenshot, sid).document_type == DOCUMENT_TYPE_PAYMENT_PROOF
        finally:
            db.close()


class TestScheduleRawJsonPurge:
    def test_purges_schedule_draft_raw_json_past_window(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        screenshot_id = _make_screenshot(business_id, user_id)
        fake = FakeOfferLetterVisionClient(
            extraction=OfferLetterExtraction(
                installments=[{"sequence": 0, "amount_minor": 1, "due_date": "2026-01-01"}],
                confidence={"installments": 0.9},
                unreadable=False,
                raw_json={"keep": "until purged"},
            )
        )
        asyncio.run(
            worker_module.extract_screenshot({"vision": fake, "job_try": 1}, str(screenshot_id))
        )
        old = datetime.now(tz=UTC) - timedelta(days=31)
        db = TestingSessionLocal()
        db.execute(
            update(ScheduleDraft)
            .where(ScheduleDraft.screenshot_id == screenshot_id)
            .values(created_at=old)
        )
        db.commit()
        db.close()

        purged = asyncio.run(worker_module.purge_raw_json({}))
        assert purged == {"purged": 1}
        assert _load_schedule_draft(screenshot_id).raw_json is None
