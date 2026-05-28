"""Phase 8.1 (eager upload on attach) tests.

Covers the new orphan-screenshot path:
  - upload without transaction_id → 200, row's transaction_id IS NULL
  - transaction create with screenshot_id atomically links the orphan
  - cross-business / already-linked / missing → 403 / 409 / 404
  - purge_orphan_screenshots soft-deletes stale orphans + storage bytes,
    leaves recent ones and linked screenshots alone

Re-uses the same TestClient + dependency-override setup as the Phase 5/8
tests. Vision client is injected as a fake when the worker code is exercised.
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
from app.db.models import Profile, Screenshot
from app.main import app
from app.worker import worker as worker_module


# --- shared test DB plumbing (same shape as test_phase8_extraction) -----

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
    resp = client.post("/businesses", json={"id": str(business_id), "name": "Ledger Co"})
    assert resp.status_code == 200, resp.text
    return business_id


def _upload_orphan(client: TestClient, business_id: uuid.UUID) -> dict:
    files = {"file": ("proof.jpg", JPEG_MAGIC + b"x" * 64, "image/jpeg")}
    # No transaction_id field — this is what makes it an orphan upload.
    data = {"original_file_size": "64"}
    resp = client.post(f"/businesses/{business_id}/screenshots", files=files, data=data)
    assert resp.status_code == 200, resp.text
    return resp.json()


# --- orphan upload ------------------------------------------------------


class TestOrphanUpload:
    def test_upload_without_transaction_id_succeeds(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)

        body = _upload_orphan(client, business_id)

        assert body["transaction_id"] is None
        assert body["business_id"] == str(business_id)
        # `draft_status` is present in the response shape. The actual value
        # depends on whether the test environment has an arq Redis pool —
        # TestClient skips the lifespan, so the pool is None here and the
        # field comes back as null. The worker-level enqueue path is covered
        # in test_phase8_extraction.py.
        assert "draft_status" in body

    def test_existing_upload_with_transaction_id_still_works(self, client_for):
        # Regression guard: relaxing the route must not break the standard
        # transaction-linked upload flow that everything before 8.1 uses.
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        transaction_id = uuid.uuid4()
        resp = client.post(
            f"/businesses/{business_id}/transactions",
            json={
                "id": str(transaction_id),
                "amount_minor": 100,
                "currency": "NGN",
                "direction": "money_in",
                "transaction_date": "2026-05-24",
                "counterparty_name": "X",
            },
        )
        assert resp.status_code == 200
        files = {"file": ("proof.jpg", JPEG_MAGIC + b"x" * 64, "image/jpeg")}
        data = {"transaction_id": str(transaction_id), "original_file_size": "64"}
        resp = client.post(f"/businesses/{business_id}/screenshots", files=files, data=data)
        assert resp.status_code == 200
        body = resp.json()
        assert body["transaction_id"] == str(transaction_id)


# --- link on transaction create ----------------------------------------


class TestLinkOnTransactionCreate:
    def test_create_with_screenshot_id_links_atomically(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        orphan = _upload_orphan(client, business_id)
        screenshot_id = orphan["id"]
        transaction_id = uuid.uuid4()

        resp = client.post(
            f"/businesses/{business_id}/transactions",
            json={
                "id": str(transaction_id),
                "amount_minor": 850000,
                "currency": "NGN",
                "direction": "money_in",
                "transaction_date": "2026-05-24",
                "counterparty_name": "Adewale",
                "screenshot_id": screenshot_id,
            },
        )
        assert resp.status_code == 200, resp.text

        # Confirm the link landed in the same commit as the txn insert.
        db = TestingSessionLocal()
        try:
            sshot = db.get(Screenshot, uuid.UUID(screenshot_id))
            assert sshot is not None
            assert str(sshot.transaction_id) == str(transaction_id)
        finally:
            db.close()

    def test_create_with_missing_screenshot_id_returns_404(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        resp = client.post(
            f"/businesses/{business_id}/transactions",
            json={
                "id": str(uuid.uuid4()),
                "amount_minor": 100,
                "currency": "NGN",
                "direction": "money_in",
                "transaction_date": "2026-05-24",
                "counterparty_name": "X",
                "screenshot_id": str(uuid.uuid4()),
            },
        )
        assert resp.status_code == 404

    def test_create_with_cross_business_screenshot_returns_403(self, client_for):
        owner_id = uuid.uuid4()
        client_owner = client_for(owner_id, "owner@example.com")
        business_a = _create_business(client_owner)
        orphan = _upload_orphan(client_owner, business_a)

        # Owner has a second business; the orphan belongs to the first.
        business_b = _create_business(client_owner)
        resp = client_owner.post(
            f"/businesses/{business_b}/transactions",
            json={
                "id": str(uuid.uuid4()),
                "amount_minor": 100,
                "currency": "NGN",
                "direction": "money_in",
                "transaction_date": "2026-05-24",
                "counterparty_name": "X",
                "screenshot_id": orphan["id"],
            },
        )
        assert resp.status_code == 403

    def test_create_with_already_linked_screenshot_returns_409(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        orphan = _upload_orphan(client, business_id)
        screenshot_id = orphan["id"]

        # First create links it.
        first_txn = uuid.uuid4()
        resp = client.post(
            f"/businesses/{business_id}/transactions",
            json={
                "id": str(first_txn),
                "amount_minor": 100,
                "currency": "NGN",
                "direction": "money_in",
                "transaction_date": "2026-05-24",
                "counterparty_name": "X",
                "screenshot_id": screenshot_id,
            },
        )
        assert resp.status_code == 200

        # Second create attempts to link the same screenshot — must reject.
        resp = client.post(
            f"/businesses/{business_id}/transactions",
            json={
                "id": str(uuid.uuid4()),
                "amount_minor": 200,
                "currency": "NGN",
                "direction": "money_in",
                "transaction_date": "2026-05-24",
                "counterparty_name": "Y",
                "screenshot_id": screenshot_id,
            },
        )
        assert resp.status_code == 409

    def test_create_idempotent_relink_same_txn_is_ok(self, client_for):
        # Replay of the same create with the same screenshot must not 409.
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)
        orphan = _upload_orphan(client, business_id)
        screenshot_id = orphan["id"]
        transaction_id = uuid.uuid4()

        payload = {
            "id": str(transaction_id),
            "amount_minor": 100,
            "currency": "NGN",
            "direction": "money_in",
            "transaction_date": "2026-05-24",
            "counterparty_name": "X",
            "screenshot_id": screenshot_id,
        }
        first = client.post(f"/businesses/{business_id}/transactions", json=payload)
        assert first.status_code == 200
        # Standard idempotency on transactions returns the existing row, so this
        # never even reaches _link_orphan_screenshot.
        second = client.post(f"/businesses/{business_id}/transactions", json=payload)
        assert second.status_code == 200


# --- orphan purge cron --------------------------------------------------


class TestOrphanPurge:
    def test_purges_old_orphans_keeps_recent_and_linked(self, client_for):
        user_id = uuid.uuid4()
        client = client_for(user_id, "u@example.com")
        business_id = _create_business(client)

        old_orphan = _upload_orphan(client, business_id)
        recent_orphan = _upload_orphan(client, business_id)

        # Linked screenshot (not orphan) — should be untouched.
        transaction_id = uuid.uuid4()
        client.post(
            f"/businesses/{business_id}/transactions",
            json={
                "id": str(transaction_id),
                "amount_minor": 100,
                "currency": "NGN",
                "direction": "money_in",
                "transaction_date": "2026-05-24",
                "counterparty_name": "X",
            },
        )
        linked = _upload_orphan(client, business_id)  # uploaded as orphan first
        # Now link it via a second screenshot upload that targets the txn —
        # actually easier: just patch the linked row directly to set
        # transaction_id, mirroring what the link path would do.
        db = TestingSessionLocal()
        try:
            db.execute(
                update(Screenshot)
                .where(Screenshot.id == uuid.UUID(linked["id"]))
                .values(transaction_id=transaction_id)
            )
            # Backdate the old orphan AND the linked one so we can prove the
            # purge respects transaction_id rather than just age.
            old_cutoff = datetime.now(tz=UTC) - timedelta(days=8)
            db.execute(
                update(Screenshot)
                .where(Screenshot.id == uuid.UUID(old_orphan["id"]))
                .values(created_at=old_cutoff)
            )
            db.execute(
                update(Screenshot)
                .where(Screenshot.id == uuid.UUID(linked["id"]))
                .values(created_at=old_cutoff)
            )
            db.commit()
        finally:
            db.close()

        result = asyncio.run(worker_module.purge_orphan_screenshots({}))
        assert result == {"purged": 1}

        # Old orphan: soft-deleted.
        db = TestingSessionLocal()
        try:
            old_row = db.get(Screenshot, uuid.UUID(old_orphan["id"]))
            assert old_row.deleted_at is not None
            recent_row = db.get(Screenshot, uuid.UUID(recent_orphan["id"]))
            assert recent_row.deleted_at is None
            linked_row = db.get(Screenshot, uuid.UUID(linked["id"]))
            assert linked_row.deleted_at is None  # linked rows are protected
        finally:
            db.close()

    def test_purge_with_no_orphans_is_a_noop(self):
        # Doesn't crash, returns 0.
        result = asyncio.run(worker_module.purge_orphan_screenshots({}))
        assert result == {"purged": 0}
