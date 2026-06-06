from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.api.deps import get_current_user, get_db
from app.db.base import Base
from app.db.models import Profile
from app.db.models.counterparty_type import (
    DEFAULT_COUNTERPARTY_TYPES,
    seed_counterparty_type_id,
)
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


def _type_payload(**overrides):
    payload = {"id": str(uuid.uuid4()), "name": "land_owner"}
    payload.update(overrides)
    return payload


def test_create_business_seeds_default_counterparty_types(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)

    listed = client.get(f"/businesses/{business_id}/counterparty-types")
    assert listed.status_code == 200, listed.text
    names = [row["name"] for row in listed.json()]
    assert sorted(names) == sorted(DEFAULT_COUNTERPARTY_TYPES)
    assert all(row["is_archived"] is False for row in listed.json())


def test_member_can_create_list_patch_and_soft_delete_type(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _type_payload(name="Land Owner")

    created = client.post(f"/businesses/{business_id}/counterparty-types", json=payload)
    assert created.status_code == 200, created.text
    body = created.json()
    assert body["business_id"] == str(business_id)
    assert body["name"] == "Land Owner"
    assert body["normalized_name"] == "land_owner"
    assert body["is_archived"] is False

    listed = client.get(f"/businesses/{business_id}/counterparty-types")
    assert payload["id"] in [row["id"] for row in listed.json()]

    patched = client.patch(
        f"/businesses/{business_id}/counterparty-types/{payload['id']}",
        json={"name": "Land Buyer", "is_archived": True},
    )
    assert patched.status_code == 200, patched.text
    assert patched.json()["normalized_name"] == "land_buyer"
    assert patched.json()["is_archived"] is True

    deleted = client.delete(f"/businesses/{business_id}/counterparty-types/{payload['id']}")
    assert deleted.status_code == 204
    after_delete = client.get(f"/businesses/{business_id}/counterparty-types")
    assert payload["id"] not in [row["id"] for row in after_delete.json()]


def test_list_excludes_soft_deleted(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _type_payload(name="investor")
    client.post(f"/businesses/{business_id}/counterparty-types", json=payload)
    client.delete(f"/businesses/{business_id}/counterparty-types/{payload['id']}")

    listed = client.get(f"/businesses/{business_id}/counterparty-types")
    assert "investor" not in [row["name"] for row in listed.json()]
    assert (
        client.get(
            f"/businesses/{business_id}/counterparty-types/{payload['id']}"
        ).status_code
        == 404
    )


def test_post_type_is_idempotent_by_client_uuid(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _type_payload(name="agent")

    first = client.post(f"/businesses/{business_id}/counterparty-types", json=payload)
    second = client.post(f"/businesses/{business_id}/counterparty-types", json=payload)
    assert first.status_code == second.status_code == 200
    assert first.json()["id"] == second.json()["id"]

    matches = [
        row
        for row in client.get(f"/businesses/{business_id}/counterparty-types").json()
        if row["id"] == payload["id"]
    ]
    assert len(matches) == 1


def test_post_type_same_uuid_cross_business_or_deleted_returns_404(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client, "One")
    other_business_id = _create_business(client, "Two")
    payload = _type_payload(name="tenant")
    client.post(f"/businesses/{business_id}/counterparty-types", json=payload)

    cross_business = client.post(
        f"/businesses/{other_business_id}/counterparty-types", json=payload
    )
    assert cross_business.status_code == 404

    client.delete(f"/businesses/{business_id}/counterparty-types/{payload['id']}")
    deleted = client.post(f"/businesses/{business_id}/counterparty-types", json=payload)
    assert deleted.status_code == 404


def test_patch_type_rejects_stale_expected_updated_at(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _type_payload()
    created = client.post(
        f"/businesses/{business_id}/counterparty-types", json=payload
    ).json()
    original_updated_at = created["updated_at"]

    matching = client.patch(
        f"/businesses/{business_id}/counterparty-types/{payload['id']}",
        json={"name": "land_owner_v2", "expected_updated_at": original_updated_at},
    )
    assert matching.status_code == 200

    rejected = client.patch(
        f"/businesses/{business_id}/counterparty-types/{payload['id']}",
        json={"name": "stale", "expected_updated_at": "1999-01-01T00:00:00+00:00"},
    )
    assert rejected.status_code == 409


def test_update_type_rejects_explicit_null_name(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _type_payload()
    client.post(f"/businesses/{business_id}/counterparty-types", json=payload)

    resp = client.patch(
        f"/businesses/{business_id}/counterparty-types/{payload['id']}",
        json={"name": None},
    )
    assert resp.status_code == 422, resp.text


def test_non_member_gets_404_for_counterparty_types(client_for):
    alice = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(alice)

    bob = client_for(uuid.uuid4(), "bob@example.com")
    resp = bob.get(f"/businesses/{business_id}/counterparty-types")
    assert resp.status_code == 404


def test_list_counterparty_types_supports_search(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    client.post(
        f"/businesses/{business_id}/counterparty-types",
        json=_type_payload(name="Property Agent"),
    )

    search = client.get(
        f"/businesses/{business_id}/counterparty-types?search=property"
    )
    assert search.status_code == 200
    assert "Property Agent" in [row["name"] for row in search.json()]


def test_default_seed_uses_deterministic_per_business_id(client_for):
    """The on-device one-time import recomputes these ids to dedupe; lock the
    contract so a client create POST for a default lands as an upsert."""
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)

    listed = {row["name"]: row["id"] for row in client.get(
        f"/businesses/{business_id}/counterparty-types"
    ).json()}
    expected = str(seed_counterparty_type_id(business_id, "customer"))
    assert listed["customer"] == expected

    # Re-posting that exact id is idempotent (acts as the client-side import).
    resp = client.post(
        f"/businesses/{business_id}/counterparty-types",
        json={"id": expected, "name": "customer"},
    )
    assert resp.status_code == 200
    assert resp.json()["id"] == expected
