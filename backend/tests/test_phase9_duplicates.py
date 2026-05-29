from __future__ import annotations

import uuid
from datetime import UTC, datetime

from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.api.deps import get_current_user, get_db
from app.core.config import settings
from app.db.base import Base
from app.db.models import AuditEvent, Profile, Transaction
from app.main import app

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
TestingSessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)

JPEG_MAGIC = b"\xff\xd8\xff\xe0"


def setup_function():
    settings.proof_storage_backend = "local"
    Base.metadata.create_all(bind=engine)


def teardown_function():
    Base.metadata.drop_all(bind=engine)
    app.dependency_overrides.clear()


def _override_db():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()


def _client(user_id: uuid.UUID | None = None) -> TestClient:
    user_id = user_id or uuid.uuid4()
    db = TestingSessionLocal()
    db.add(Profile(id=user_id, email=f"{user_id}@example.com"))
    db.commit()
    db.close()

    def _override_user():
        s = TestingSessionLocal()
        try:
            yield s.get(Profile, user_id)
        finally:
            s.close()

    app.dependency_overrides[get_db] = _override_db
    app.dependency_overrides[get_current_user] = _override_user
    return TestClient(app)


def _business(client: TestClient) -> uuid.UUID:
    business_id = uuid.uuid4()
    resp = client.post("/businesses", json={"id": str(business_id), "name": "Ledger Co"})
    assert resp.status_code == 200, resp.text
    return business_id


def _transaction(
    client: TestClient,
    business_id: uuid.UUID,
    *,
    transaction_id: uuid.UUID | None = None,
    amount_minor: int = 125000,
    direction: str = "money_in",
    transaction_date: str = "2026-05-24",
    counterparty_name: str = "Adewale Supplies",
    counterparty_id: str | None = None,
    reference_number: str | None = None,
) -> dict:
    resp = client.post(
        f"/businesses/{business_id}/transactions",
        json={
            "id": str(transaction_id or uuid.uuid4()),
            "amount_minor": amount_minor,
            "currency": "NGN",
            "direction": direction,
            "transaction_date": transaction_date,
            "counterparty_id": counterparty_id,
            "counterparty_name": counterparty_name,
            "reference_number": reference_number,
        },
    )
    assert resp.status_code == 200, resp.text
    return resp.json()


def _upload(
    client: TestClient,
    business_id: uuid.UUID,
    transaction_id: str,
    content: bytes,
) -> dict:
    body = JPEG_MAGIC + content
    resp = client.post(
        f"/businesses/{business_id}/screenshots",
        data={
            "transaction_id": transaction_id,
            "original_file_size": str(len(body)),
            "width": "1080",
            "height": "1920",
        },
        files={"file": ("proof.jpg", body, "image/jpeg")},
    )
    assert resp.status_code == 200, resp.text
    return resp.json()


def _duplicates(client: TestClient, business_id: uuid.UUID, payload: dict) -> list[dict]:
    resp = client.post(f"/businesses/{business_id}/transactions/duplicate-candidates", json=payload)
    assert resp.status_code == 200, resp.text
    return resp.json()


def test_uploading_identical_proof_bytes_stores_same_sha256(tmp_path):
    settings.proof_storage_local_dir = str(tmp_path / "proofs")
    client = _client()
    business_id = _business(client)
    tx1 = _transaction(client, business_id)
    tx2 = _transaction(client, business_id, counterparty_name="Other")

    one = _upload(client, business_id, tx1["id"], b"same")
    two = _upload(client, business_id, tx2["id"], b"same")

    assert one["image_sha256"] == two["image_sha256"]
    assert len(one["image_sha256"]) == 64


def test_duplicate_endpoint_returns_same_proof_candidate(tmp_path):
    settings.proof_storage_local_dir = str(tmp_path / "proofs")
    client = _client()
    business_id = _business(client)
    tx1 = _transaction(client, business_id)
    tx2 = _transaction(client, business_id, counterparty_name="Other")
    _upload(client, business_id, tx1["id"], b"same")
    screenshot = _upload(client, business_id, tx2["id"], b"same")

    rows = _duplicates(
        client,
        business_id,
        {
            "amount_minor": 999,
            "direction": "money_in",
            "transaction_date": "2026-05-25",
            "screenshot_id": screenshot["id"],
            "exclude_transaction_id": tx2["id"],
        },
    )

    assert rows[0]["transaction"]["id"] == tx1["id"]
    assert "same_proof" in rows[0]["reasons"]


def test_duplicate_endpoint_normalizes_reference_case_and_spacing():
    client = _client()
    business_id = _business(client)
    tx = _transaction(client, business_id, reference_number="TRF 240  ABC")

    rows = _duplicates(
        client,
        business_id,
        {
            "amount_minor": 1,
            "direction": "money_out",
            "transaction_date": "2026-05-25",
            "reference_number": "trf240abc",
        },
    )

    assert rows[0]["transaction"]["id"] == tx["id"]
    assert rows[0]["reasons"] == ["same_reference"]


def test_duplicate_endpoint_returns_amount_date_counterparty_candidate():
    client = _client()
    business_id = _business(client)
    tx = _transaction(client, business_id, amount_minor=50000, counterparty_name="Jane  Doe")

    rows = _duplicates(
        client,
        business_id,
        {
            "amount_minor": 50000,
            "direction": "money_in",
            "transaction_date": "2026-05-24",
            "counterparty_name": "jane doe",
        },
    )

    assert rows[0]["transaction"]["id"] == tx["id"]
    assert "same_amount_date_name" in rows[0]["reasons"]


def test_duplicate_endpoint_writes_audit_event():
    client = _client()
    business_id = _business(client)
    _transaction(client, business_id, reference_number="ABC 123")

    rows = _duplicates(
        client,
        business_id,
        {
            "amount_minor": 125000,
            "direction": "money_in",
            "transaction_date": "2026-05-24",
            "reference_number": "abc123",
        },
    )

    assert rows
    db = TestingSessionLocal()
    try:
        event = (
            db.query(AuditEvent)
            .filter(AuditEvent.business_id == business_id)
            .filter(AuditEvent.action == "duplicate_check")
            .one()
        )
        assert event.entity_type == "transaction"
        assert event.status == "duplicate_found"
    finally:
        db.close()


def test_duplicate_endpoint_excludes_cross_business_soft_deleted_and_self(tmp_path):
    settings.proof_storage_local_dir = str(tmp_path / "proofs")
    client = _client()
    business_id = _business(client)
    other_business_id = _business(client)
    tx = _transaction(client, business_id, reference_number="REF1")
    deleted = _transaction(
        client,
        business_id,
        reference_number="REF1",
        counterparty_name="Deleted",
    )
    _transaction(client, other_business_id, reference_number="REF1", counterparty_name="Other Biz")
    db = TestingSessionLocal()
    db.get(Transaction, uuid.UUID(deleted["id"])).deleted_at = datetime(2026, 5, 28, tzinfo=UTC)
    db.commit()
    db.close()

    rows = _duplicates(
        client,
        business_id,
        {
            "amount_minor": 1,
            "direction": "money_in",
            "transaction_date": "2026-05-24",
            "reference_number": "REF1",
            "exclude_transaction_id": tx["id"],
        },
    )

    assert rows == []


def test_missing_or_cross_business_screenshot_id_does_not_leak(tmp_path):
    settings.proof_storage_local_dir = str(tmp_path / "proofs")
    client = _client()
    business_id = _business(client)
    other_business_id = _business(client)
    other_tx = _transaction(client, other_business_id)
    other_screenshot = _upload(client, other_business_id, other_tx["id"], b"same")

    missing = _duplicates(
        client,
        business_id,
        {
            "amount_minor": 1,
            "direction": "money_in",
            "transaction_date": "2026-05-24",
            "screenshot_id": str(uuid.uuid4()),
        },
    )
    cross = _duplicates(
        client,
        business_id,
        {
            "amount_minor": 1,
            "direction": "money_in",
            "transaction_date": "2026-05-24",
            "screenshot_id": other_screenshot["id"],
        },
    )

    assert missing == []
    assert cross == []
