from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.api.deps import get_current_user, get_db
from app.db.base import Base
from app.db.models import BusinessMember, Profile
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


def _create_business(client: TestClient, name: str = "Ledger Co") -> uuid.UUID:
    business_id = uuid.uuid4()
    resp = client.post("/businesses", json={"id": str(business_id), "name": name})
    assert resp.status_code == 200, resp.text
    return business_id


def _counterparty_payload(**overrides):
    payload = {
        "id": str(uuid.uuid4()),
        "name": "Adewale   Supplies",
        "type": "supplier",
        "phone": "+2348000000000",
        "notes": "Blocks and cement",
    }
    payload.update(overrides)
    return payload


def _transaction_payload(**overrides):
    payload = {
        "id": str(uuid.uuid4()),
        "amount_minor": 125000,
        "currency": "NGN",
        "direction": "money_in",
        "transaction_date": "2026-05-23",
        "counterparty_name": "Adewale Supplies",
    }
    payload.update(overrides)
    return payload


def test_member_can_create_list_patch_and_soft_delete_counterparty(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _counterparty_payload()

    created = client.post(f"/businesses/{business_id}/counterparties", json=payload)
    assert created.status_code == 200, created.text
    body = created.json()
    assert body["business_id"] == str(business_id)
    assert body["name"] == "Adewale   Supplies"
    assert body["normalized_name"] == "adewale supplies"

    listed = client.get(f"/businesses/{business_id}/counterparties")
    assert listed.status_code == 200
    assert [row["id"] for row in listed.json()] == [payload["id"]]

    patched = client.patch(
        f"/businesses/{business_id}/counterparties/{payload['id']}",
        json={"name": "Adewale Supplies Ltd", "type": "customer", "phone": None},
    )
    assert patched.status_code == 200, patched.text
    assert patched.json()["normalized_name"] == "adewale supplies ltd"
    assert patched.json()["type"] == "customer"
    assert patched.json()["phone"] is None

    deleted = client.delete(f"/businesses/{business_id}/counterparties/{payload['id']}")
    assert deleted.status_code == 204
    after_delete = client.get(f"/businesses/{business_id}/counterparties")
    assert after_delete.json() == []


def test_non_member_gets_404_for_counterparties(client_for):
    alice = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(alice)

    bob = client_for(uuid.uuid4(), "bob@example.com")
    resp = bob.get(f"/businesses/{business_id}/counterparties")
    assert resp.status_code == 404


def test_cross_business_counterparty_access_returns_404(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client, "One")
    other_business_id = _create_business(client, "Two")
    payload = _counterparty_payload()
    client.post(f"/businesses/{business_id}/counterparties", json=payload)

    resp = client.get(f"/businesses/{other_business_id}/counterparties/{payload['id']}")
    assert resp.status_code == 404


def test_post_counterparty_is_idempotent_by_client_uuid(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _counterparty_payload(name="Musa Transport")

    first = client.post(f"/businesses/{business_id}/counterparties", json=payload)
    second = client.post(f"/businesses/{business_id}/counterparties", json=payload)
    assert first.status_code == second.status_code == 200
    assert first.json()["id"] == second.json()["id"]

    listed = client.get(f"/businesses/{business_id}/counterparties")
    assert len(listed.json()) == 1


def test_post_counterparty_same_uuid_cross_business_or_deleted_returns_404(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client, "One")
    other_business_id = _create_business(client, "Two")
    payload = _counterparty_payload(name="Musa Transport")
    client.post(f"/businesses/{business_id}/counterparties", json=payload)

    cross_business = client.post(f"/businesses/{other_business_id}/counterparties", json=payload)
    assert cross_business.status_code == 404

    client.delete(f"/businesses/{business_id}/counterparties/{payload['id']}")
    deleted = client.post(f"/businesses/{business_id}/counterparties", json=payload)
    assert deleted.status_code == 404


def test_update_counterparty_rejects_explicit_null_required_fields(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _counterparty_payload()
    client.post(f"/businesses/{business_id}/counterparties", json=payload)

    for field in ("name", "type"):
        resp = client.patch(
            f"/businesses/{business_id}/counterparties/{payload['id']}",
            json={field: None},
        )
        assert resp.status_code == 422, resp.text


def test_transaction_create_rejects_cross_business_counterparty(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client, "One")
    other_business_id = _create_business(client, "Two")
    payload = _counterparty_payload(name="Other Business Person")
    client.post(f"/businesses/{other_business_id}/counterparties", json=payload)

    resp = client.post(
        f"/businesses/{business_id}/transactions",
        json=_transaction_payload(counterparty_id=payload["id"]),
    )
    assert resp.status_code == 422, resp.text


def test_transaction_create_rejects_soft_deleted_counterparty(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _counterparty_payload()
    client.post(f"/businesses/{business_id}/counterparties", json=payload)
    client.delete(f"/businesses/{business_id}/counterparties/{payload['id']}")

    resp = client.post(
        f"/businesses/{business_id}/transactions",
        json=_transaction_payload(counterparty_id=payload["id"]),
    )
    assert resp.status_code == 422, resp.text


def test_counterparty_summary_splits_totals_by_direction(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    counterparty = _counterparty_payload(name="Adewale Supplies")
    client.post(f"/businesses/{business_id}/counterparties", json=counterparty)

    client.post(
        f"/businesses/{business_id}/transactions",
        json=_transaction_payload(
            amount_minor=10000,
            direction="money_in",
            transaction_date="2026-05-20",
            counterparty_id=counterparty["id"],
        ),
    )
    client.post(
        f"/businesses/{business_id}/transactions",
        json=_transaction_payload(
            amount_minor=2500,
            direction="money_out",
            transaction_date="2026-05-23",
            counterparty_id=counterparty["id"],
        ),
    )
    unrelated = _transaction_payload(amount_minor=9999, direction="money_in")
    client.post(f"/businesses/{business_id}/transactions", json=unrelated)

    resp = client.get(f"/businesses/{business_id}/counterparties/{counterparty['id']}/summary")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["total_in"] == 10000
    assert body["total_out"] == 2500
    assert body["last_transaction_date"] == "2026-05-23"
    assert body["transaction_count"] == 2


def test_list_counterparties_supports_search_and_type_filter(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    client.post(
        f"/businesses/{business_id}/counterparties",
        json=_counterparty_payload(name="Adewale   Supplies", type="supplier"),
    )
    client.post(
        f"/businesses/{business_id}/counterparties",
        json=_counterparty_payload(name="Musa Transport", type="driver"),
    )

    search = client.get(f"/businesses/{business_id}/counterparties?search=adewale supplies")
    assert search.status_code == 200
    assert [row["name"] for row in search.json()] == ["Adewale   Supplies"]

    type_filter = client.get(f"/businesses/{business_id}/counterparties?type=driver")
    assert type_filter.status_code == 200
    assert [row["name"] for row in type_filter.json()] == ["Musa Transport"]


def test_member_can_create_counterparty(client_for):
    alice = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(alice)

    bob_id = uuid.uuid4()
    bob = client_for(bob_id, "bob@example.com")
    db = TestingSessionLocal()
    db.add(BusinessMember(business_id=business_id, user_id=bob_id, role="member"))
    db.commit()
    db.close()

    resp = bob.post(f"/businesses/{business_id}/counterparties", json=_counterparty_payload())
    assert resp.status_code == 200, resp.text
