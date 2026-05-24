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


def _make_user(user_id: uuid.UUID, email: str) -> Profile:
    db = TestingSessionLocal()
    profile = Profile(id=user_id, email=email)
    db.add(profile)
    db.commit()
    db.refresh(profile)
    db.close()
    return profile


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


def _transaction_payload(**overrides):
    payload = {
        "id": str(uuid.uuid4()),
        "amount_minor": 125000,
        "currency": "NGN",
        "direction": "money_in",
        "transaction_date": "2026-05-23",
        "counterparty_name": "Adewale Supplies",
        "category": "customer payment",
        "note": "Deposit",
        "bank_name": "GTBank",
        "reference_number": "REF123",
    }
    payload.update(overrides)
    return payload


def test_create_transaction_requires_membership_and_sets_created_by(client_for):
    user_id = uuid.uuid4()
    client = client_for(user_id, "alice@example.com")
    business_id = _create_business(client)

    resp = client.post(
        f"/businesses/{business_id}/transactions",
        json=_transaction_payload(),
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["business_id"] == str(business_id)
    assert body["created_by"] == str(user_id)
    assert body["amount_minor"] == 125000

    bob = client_for(uuid.uuid4(), "bob@example.com")
    denied = bob.post(
        f"/businesses/{business_id}/transactions",
        json=_transaction_payload(),
    )
    assert denied.status_code == 404


def test_list_filters_and_excludes_soft_deleted(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    keep = _transaction_payload(counterparty_name="Adewale Supplies", direction="money_in")
    remove = _transaction_payload(counterparty_name="Musa Transport", direction="money_out")

    client.post(f"/businesses/{business_id}/transactions", json=keep)
    client.post(f"/businesses/{business_id}/transactions", json=remove)
    delete_resp = client.delete(f"/businesses/{business_id}/transactions/{remove['id']}")
    assert delete_resp.status_code == 204

    all_resp = client.get(f"/businesses/{business_id}/transactions")
    assert all_resp.status_code == 200
    rows = all_resp.json()
    assert len(rows) == 1
    assert rows[0]["id"] == keep["id"]

    search_resp = client.get(f"/businesses/{business_id}/transactions?search=Adewale")
    assert len(search_resp.json()) == 1

    direction_resp = client.get(f"/businesses/{business_id}/transactions?direction=money_out")
    assert direction_resp.json() == []


def test_patch_and_delete_preserve_business_boundaries(client_for):
    alice = client_for(uuid.uuid4(), "alice@example.com")
    alice_business_id = _create_business(alice, "Alice Co")
    other_business_id = _create_business(alice, "Other Co")
    payload = _transaction_payload()
    alice.post(f"/businesses/{alice_business_id}/transactions", json=payload)

    wrong_business = alice.patch(
        f"/businesses/{other_business_id}/transactions/{payload['id']}",
        json={"note": "Wrong"},
    )
    assert wrong_business.status_code == 404

    patched = alice.patch(
        f"/businesses/{alice_business_id}/transactions/{payload['id']}",
        json={"note": "Updated", "amount_minor": 250000},
    )
    assert patched.status_code == 200
    assert patched.json()["note"] == "Updated"
    assert patched.json()["amount_minor"] == 250000

    bob = client_for(uuid.uuid4(), "bob@example.com")
    bob_delete = bob.delete(f"/businesses/{alice_business_id}/transactions/{payload['id']}")
    assert bob_delete.status_code == 404


def test_member_can_create_transaction(client_for):
    alice_id = uuid.uuid4()
    alice = client_for(alice_id, "alice@example.com")
    business_id = _create_business(alice)

    bob_id = uuid.uuid4()
    bob = client_for(bob_id, "bob@example.com")
    db = TestingSessionLocal()
    db.add(BusinessMember(business_id=business_id, user_id=bob_id, role="member"))
    db.commit()
    db.close()

    resp = bob.post(f"/businesses/{business_id}/transactions", json=_transaction_payload())
    assert resp.status_code == 200, resp.text
    assert resp.json()["created_by"] == str(bob_id)
