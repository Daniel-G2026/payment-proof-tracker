"""Phase 13 Cut 3 — link money-in transactions to a unit + optional earmark.

Covers persisting/returning unit_id and earmarked_installment_id on create and
update, clearing them, and rejecting a unit from another business.
"""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.api.deps import get_current_user, get_db
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


def _setup(client: TestClient) -> tuple[uuid.UUID, uuid.UUID, str]:
    """Returns (business_id, unit_id, first_installment_id)."""
    business_id = uuid.uuid4()
    client.post("/businesses", json={"id": str(business_id), "name": "Estate Co"})
    project_id = uuid.uuid4()
    client.post(
        f"/businesses/{business_id}/projects",
        json={"id": str(project_id), "name": "Sunrise"},
    )
    unit_id = uuid.uuid4()
    client.post(
        f"/businesses/{business_id}/units",
        json={"id": str(unit_id), "project_id": str(project_id), "label": "Plot 12"},
    )
    schedule = client.post(
        f"/businesses/{business_id}/units/{unit_id}/schedule-versions",
        json={
            "id": str(uuid.uuid4()),
            "source": "manual",
            "installments": [
                {
                    "id": str(uuid.uuid4()),
                    "sequence": 1,
                    "amount_minor": 50000000,
                    "due_date": "2026-01-31",
                },
            ],
        },
    )
    installment_id = schedule.json()["installments"][0]["id"]
    return business_id, unit_id, installment_id


def _money_in(unit_id=None, earmark=None) -> dict:
    body = {
        "id": str(uuid.uuid4()),
        "amount_minor": 30000000,
        "currency": "NGN",
        "direction": "money_in",
        "transaction_date": "2026-01-20",
    }
    if unit_id is not None:
        body["unit_id"] = str(unit_id)
    if earmark is not None:
        body["earmarked_installment_id"] = str(earmark)
    return body


def test_create_money_in_linked_to_unit_with_earmark(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id, unit_id, installment_id = _setup(client)

    body = _money_in(unit_id=unit_id, earmark=installment_id)
    created = client.post(f"/businesses/{business_id}/transactions", json=body)
    assert created.status_code == 200, created.text
    out = created.json()
    assert out["unit_id"] == str(unit_id)
    assert out["earmarked_installment_id"] == installment_id

    got = client.get(f"/businesses/{business_id}/transactions/{body['id']}").json()
    assert got["unit_id"] == str(unit_id)
    assert got["earmarked_installment_id"] == installment_id


def test_create_money_in_without_link(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id, _, _ = _setup(client)
    created = client.post(f"/businesses/{business_id}/transactions", json=_money_in())
    assert created.status_code == 200
    assert created.json()["unit_id"] is None
    assert created.json()["earmarked_installment_id"] is None


def test_update_can_link_and_then_clear_unit(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id, unit_id, installment_id = _setup(client)
    body = _money_in()
    client.post(f"/businesses/{business_id}/transactions", json=body)

    linked = client.patch(
        f"/businesses/{business_id}/transactions/{body['id']}",
        json={"unit_id": str(unit_id), "earmarked_installment_id": installment_id},
    )
    assert linked.status_code == 200, linked.text
    assert linked.json()["unit_id"] == str(unit_id)

    cleared = client.patch(
        f"/businesses/{business_id}/transactions/{body['id']}",
        json={"unit_id": None, "earmarked_installment_id": None},
    )
    assert cleared.status_code == 200
    assert cleared.json()["unit_id"] is None
    assert cleared.json()["earmarked_installment_id"] is None


def test_create_rejects_unit_from_other_business(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_a, _, _ = _setup(client)
    # A unit in a second business.
    business_b, unit_b, _ = _setup(client)
    created = client.post(
        f"/businesses/{business_a}/transactions",
        json=_money_in(unit_id=unit_b),
    )
    assert created.status_code == 422


def test_unit_link_recorded_in_audit_history(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id, unit_id, _ = _setup(client)
    body = _money_in()
    client.post(f"/businesses/{business_id}/transactions", json=body)
    client.patch(
        f"/businesses/{business_id}/transactions/{body['id']}",
        json={"unit_id": str(unit_id)},
    )
    history = client.get(
        f"/businesses/{business_id}/audit-events",
        params={"entity_type": "transaction", "entity_id": body["id"]},
    )
    assert history.status_code == 200
    # The update event records the unit_id change in its new_values diff.
    update_events = [e for e in history.json() if e["action"] == "update"]
    assert any(
        (e.get("new_values") or {}).get("unit_id") == str(unit_id) for e in update_events
    )
