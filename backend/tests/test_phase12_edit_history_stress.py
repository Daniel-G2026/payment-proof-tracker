"""Phase 12 Cut 2 — edit-history stress test.

Bursts many rapid edits (all likely within the same DB-clock second) to prove
the microsecond `created_at` ordering stays strictly monotonic and the read
endpoint returns a complete, correctly-ordered, capped history.
"""

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


@pytest.fixture
def client():
    user_id = uuid.uuid4()
    db = TestingSessionLocal()
    db.add(Profile(id=user_id, email="h@example.com"))
    db.commit()
    db.close()

    def _override_user():
        db = TestingSessionLocal()
        try:
            yield db.get(Profile, user_id)
        finally:
            db.close()

    app.dependency_overrides[get_db] = _override_db
    app.dependency_overrides[get_current_user] = _override_user
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_history_ordering_stable_under_edit_burst(client):
    bid = uuid.uuid4()
    assert client.post("/businesses", json={"id": str(bid), "name": "Co"}).status_code == 200
    tid = uuid.uuid4()
    assert client.post(
        f"/businesses/{bid}/transactions",
        json={
            "id": str(tid),
            "amount_minor": 1000,
            "currency": "NGN",
            "direction": "money_in",
            "transaction_date": "2026-05-01",
        },
    ).status_code == 200

    # 120 rapid edits, each setting amount = 2000, 3000, ... so we can verify order.
    for i in range(1, 121):
        amount = (i + 1) * 1000
        assert client.patch(
            f"/businesses/{bid}/transactions/{tid}", json={"amount_minor": amount}
        ).status_code == 200

    resp = client.get(
        f"/businesses/{bid}/audit-events",
        params={"entity_type": "transaction", "entity_id": str(tid)},
    )
    assert resp.status_code == 200
    events = resp.json()
    # 1 create + 120 updates = 121, under the 200 cap so all returned.
    assert len(events) == 121
    assert events[0]["action"] == "update"
    assert events[-1]["action"] == "create"

    # created_at must be strictly decreasing (newest first) — no ties.
    timestamps = [e["created_at"] for e in events]
    assert timestamps == sorted(timestamps, reverse=True)
    assert len(set(timestamps)) == len(timestamps)

    # The newest update is the last amount we set.
    assert events[0]["new_values"]["amount_minor"] == 121 * 1000
