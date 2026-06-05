"""Phase 12 — server-side sync_events ingest + list tests."""

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


def _event(status="failure", entity_id=None):
    return {
        "id": str(uuid.uuid4()),
        "entity_type": "transaction",
        "entity_id": entity_id or str(uuid.uuid4()),
        "action": "create",
        "status": status,
        "error_message": "No connectivity" if status == "failure" else None,
        "occurred_at": "2026-06-05T09:00:00+00:00",
    }


def test_ingest_then_list(client_for):
    client = client_for(uuid.uuid4(), "a@example.com")
    bid = _business(client)
    events = [_event(), _event(status="success")]
    resp = client.post(f"/businesses/{bid}/sync-events", json=events)
    assert resp.status_code == 200, resp.text
    assert resp.json() == {"accepted": 2, "duplicates": 0}

    listed = client.get(f"/businesses/{bid}/sync-events").json()
    assert len(listed) == 2
    # status filter works
    failures = client.get(f"/businesses/{bid}/sync-events", params={"status": "failure"}).json()
    assert len(failures) == 1
    assert failures[0]["error_message"] == "No connectivity"


def test_ingest_is_idempotent_on_reflush(client_for):
    client = client_for(uuid.uuid4(), "a@example.com")
    bid = _business(client)
    events = [_event(), _event()]
    first = client.post(f"/businesses/{bid}/sync-events", json=events).json()
    assert first == {"accepted": 2, "duplicates": 0}
    # Device didn't get the response and re-flushes the same batch.
    second = client.post(f"/businesses/{bid}/sync-events", json=events).json()
    assert second == {"accepted": 0, "duplicates": 2}
    assert len(client.get(f"/businesses/{bid}/sync-events").json()) == 2


def test_duplicate_within_a_single_batch_is_deduped(client_for):
    client = client_for(uuid.uuid4(), "a@example.com")
    bid = _business(client)
    e = _event()
    resp = client.post(f"/businesses/{bid}/sync-events", json=[e, e]).json()
    assert resp == {"accepted": 1, "duplicates": 1}


def test_non_member_cannot_ingest_or_list(client_for):
    alice = client_for(uuid.uuid4(), "alice@example.com")
    bid = _business(alice)
    bob = client_for(uuid.uuid4(), "bob@example.com")
    assert bob.post(f"/businesses/{bid}/sync-events", json=[_event()]).status_code == 404
    assert bob.get(f"/businesses/{bid}/sync-events").status_code == 404


def test_oversized_batch_rejected(client_for):
    client = client_for(uuid.uuid4(), "a@example.com")
    bid = _business(client)
    resp = client.post(f"/businesses/{bid}/sync-events", json=[_event() for _ in range(201)])
    assert resp.status_code == 422


def test_large_batch_burst_within_limit(client_for):
    client = client_for(uuid.uuid4(), "a@example.com")
    bid = _business(client)
    batch = [_event() for _ in range(200)]
    resp = client.post(f"/businesses/{bid}/sync-events", json=batch).json()
    assert resp == {"accepted": 200, "duplicates": 0}
    # Re-flush the whole 200 — all deduped, none doubled.
    again = client.post(f"/businesses/{bid}/sync-events", json=batch).json()
    assert again == {"accepted": 0, "duplicates": 200}
    assert len(client.get(f"/businesses/{bid}/sync-events", params={"limit": 500}).json()) == 200
