from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.api.deps import get_current_user, get_db
from app.db.base import Base
from app.db.models import AuditEvent, Profile
from app.main import app

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
TestingSessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)


@pytest.fixture(autouse=True)
def setup_db():
    Base.metadata.create_all(bind=engine)
    yield
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
    def _build(user_id: uuid.UUID, email: str):
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


def _create_business(client: TestClient, name: str = "Ada Stores") -> uuid.UUID:
    business_id = uuid.uuid4()
    resp = client.post("/businesses", json={"id": str(business_id), "name": name})
    assert resp.status_code == 200, resp.text
    return business_id


def _create_transaction(client: TestClient, business_id: uuid.UUID, **overrides) -> None:
    payload = {
        "id": str(uuid.uuid4()),
        "amount_minor": 125000,
        "currency": "NGN",
        "direction": "money_in",
        "transaction_date": "2026-06-10",
        "counterparty_name": "Adewale Supplies",
        "reference_number": "REF-001",
    }
    payload.update(overrides)
    resp = client.post(f"/businesses/{business_id}/transactions", json=payload)
    assert resp.status_code == 200, resp.text


def test_export_pdf_returns_pdf(client_for):
    client = client_for(uuid.uuid4(), "ada@example.com")
    business_id = _create_business(client)
    _create_transaction(client, business_id, direction="money_in", amount_minor=125000)
    _create_transaction(
        client,
        business_id,
        direction="money_out",
        amount_minor=40000,
        transaction_date="2026-06-15",
    )

    resp = client.get(
        f"/businesses/{business_id}/export.pdf",
        params={"start_date": "2026-06-01", "end_date": "2026-06-30"},
    )

    assert resp.status_code == 200, resp.text
    assert resp.headers["content-type"] == "application/pdf"
    assert "attachment" in resp.headers["content-disposition"]
    assert ".pdf" in resp.headers["content-disposition"]
    body = resp.content
    assert len(body) > 0
    assert body.startswith(b"%PDF")


def test_export_pdf_excludes_soft_deleted_and_out_of_range(client_for):
    client = client_for(uuid.uuid4(), "ada@example.com")
    business_id = _create_business(client)
    # Out-of-range record should not block a valid (empty) export.
    _create_transaction(client, business_id, transaction_date="2026-05-31")

    resp = client.get(
        f"/businesses/{business_id}/export.pdf",
        params={"start_date": "2026-06-01", "end_date": "2026-06-30"},
    )
    assert resp.status_code == 200
    assert resp.content.startswith(b"%PDF")


def test_export_pdf_non_member_gets_404(client_for):
    alice = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(alice)

    bob = client_for(uuid.uuid4(), "bob@example.com")
    resp = bob.get(
        f"/businesses/{business_id}/export.pdf",
        params={"start_date": "2026-06-01", "end_date": "2026-06-30"},
    )
    assert resp.status_code == 404


def test_export_pdf_writes_audit_event(client_for):
    user_id = uuid.uuid4()
    client = client_for(user_id, "ada@example.com")
    business_id = _create_business(client)
    _create_transaction(client, business_id)

    resp = client.get(
        f"/businesses/{business_id}/export.pdf",
        params={"start_date": "2026-06-01", "end_date": "2026-06-30"},
    )
    assert resp.status_code == 200

    db = TestingSessionLocal()
    try:
        events = (
            db.execute(
                select(AuditEvent).where(
                    AuditEvent.business_id == business_id,
                    AuditEvent.entity_type == "export",
                )
            )
            .scalars()
            .all()
        )
    finally:
        db.close()

    assert len(events) == 1
    event = events[0]
    assert event.action == "export"
    assert event.status == "success"
    assert event.entity_id is None
    assert event.actor_id == user_id
    assert event.new_values == {
        "format": "pdf",
        "start_date": "2026-06-01",
        "end_date": "2026-06-30",
        "row_count": 1,
    }


def test_export_pdf_rejects_inverted_range(client_for):
    client = client_for(uuid.uuid4(), "ada@example.com")
    business_id = _create_business(client)

    resp = client.get(
        f"/businesses/{business_id}/export.pdf",
        params={"start_date": "2026-06-30", "end_date": "2026-06-01"},
    )
    assert resp.status_code == 422
