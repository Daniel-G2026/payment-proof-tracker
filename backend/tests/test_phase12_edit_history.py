"""Phase 12 Cut 2 — edit-history read endpoint tests."""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.api.deps import get_current_user, get_db
from app.core.config import settings
from app.db.base import Base
from app.db.models import Profile
from app.main import app

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
TestingSessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)


@pytest.fixture(autouse=True)
def setup_db():
    settings.environment = "development"
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)


def _override_db():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()


def _make_user(user_id, email):
    db = TestingSessionLocal()
    db.add(Profile(id=user_id, email=email))
    db.commit()
    db.close()


@pytest.fixture
def client_for():
    def _build(user_id, email):
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


def _business(client):
    bid = uuid.uuid4()
    assert client.post("/businesses", json={"id": str(bid), "name": "Co"}).status_code == 200
    return bid


def _txn(client, bid, amount, note=None):
    tid = uuid.uuid4()
    body = {
        "id": str(tid),
        "amount_minor": amount,
        "currency": "NGN",
        "direction": "money_in",
        "transaction_date": "2026-05-01",
    }
    if note is not None:
        body["note"] = note
    assert client.post(f"/businesses/{bid}/transactions", json=body).status_code == 200
    return tid


def test_history_returns_create_then_update_newest_first(client_for):
    client = client_for(uuid.uuid4(), "a@example.com")
    bid = _business(client)
    tid = _txn(client, bid, 1000, note="first")
    # Edit it twice.
    client.patch(f"/businesses/{bid}/transactions/{tid}", json={"note": "second"})
    client.patch(f"/businesses/{bid}/transactions/{tid}", json={"amount_minor": 2000})

    resp = client.get(
        f"/businesses/{bid}/audit-events",
        params={"entity_type": "transaction", "entity_id": str(tid)},
    )
    assert resp.status_code == 200, resp.text
    events = resp.json()
    assert [e["action"] for e in events] == ["update", "update", "create"]
    # Newest update is the amount change.
    assert events[0]["old_values"]["amount_minor"] == 1000
    assert events[0]["new_values"]["amount_minor"] == 2000
    # Create carries the full snapshot in new_values.
    assert events[-1]["new_values"]["note"] == "first"


def test_history_excludes_duplicate_check_noise(client_for):
    client = client_for(uuid.uuid4(), "a@example.com")
    bid = _business(client)
    tid = _txn(client, bid, 1000)
    # Trigger an advisory duplicate check (writes a duplicate_check audit row).
    client.post(
        f"/businesses/{bid}/transactions/duplicate-candidates",
        json={
            "amount_minor": 1000,
            "direction": "money_in",
            "transaction_date": "2026-05-01",
        },
    )
    resp = client.get(
        f"/businesses/{bid}/audit-events",
        params={"entity_type": "transaction", "entity_id": str(tid)},
    )
    actions = {e["action"] for e in resp.json()}
    assert actions == {"create"}
    assert "duplicate_check" not in actions


def test_history_scoped_to_member(client_for):
    alice = client_for(uuid.uuid4(), "alice@example.com")
    bid = _business(alice)
    tid = _txn(alice, bid, 1000)

    bob = client_for(uuid.uuid4(), "bob@example.com")
    resp = bob.get(
        f"/businesses/{bid}/audit-events",
        params={"entity_type": "transaction", "entity_id": str(tid)},
    )
    assert resp.status_code == 404


def test_history_requires_entity_params(client_for):
    client = client_for(uuid.uuid4(), "a@example.com")
    bid = _business(client)
    resp = client.get(f"/businesses/{bid}/audit-events")
    assert resp.status_code == 422
