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
from app.db.models.category import DEFAULT_CONSTRUCTION_CATEGORIES
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


def _category_payload(**overrides):
    payload = {"id": str(uuid.uuid4()), "name": "Sand  Delivery"}
    payload.update(overrides)
    return payload


def test_create_business_seeds_default_categories(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)

    listed = client.get(f"/businesses/{business_id}/categories")
    assert listed.status_code == 200, listed.text
    names = [row["name"] for row in listed.json()]
    assert sorted(names) == sorted(DEFAULT_CONSTRUCTION_CATEGORIES)
    assert all(row["is_archived"] is False for row in listed.json())


def test_member_can_create_list_patch_and_soft_delete_category(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _category_payload()

    created = client.post(f"/businesses/{business_id}/categories", json=payload)
    assert created.status_code == 200, created.text
    body = created.json()
    assert body["business_id"] == str(business_id)
    assert body["name"] == "Sand  Delivery"
    assert body["normalized_name"] == "sand delivery"
    assert body["is_archived"] is False

    listed = client.get(f"/businesses/{business_id}/categories")
    assert payload["id"] in [row["id"] for row in listed.json()]

    patched = client.patch(
        f"/businesses/{business_id}/categories/{payload['id']}",
        json={"name": "Sand Delivery Ltd", "is_archived": True},
    )
    assert patched.status_code == 200, patched.text
    assert patched.json()["normalized_name"] == "sand delivery ltd"
    assert patched.json()["is_archived"] is True

    deleted = client.delete(f"/businesses/{business_id}/categories/{payload['id']}")
    assert deleted.status_code == 204
    after_delete = client.get(f"/businesses/{business_id}/categories")
    assert payload["id"] not in [row["id"] for row in after_delete.json()]


def test_list_excludes_soft_deleted(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _category_payload(name="Scaffolding")
    client.post(f"/businesses/{business_id}/categories", json=payload)
    client.delete(f"/businesses/{business_id}/categories/{payload['id']}")

    listed = client.get(f"/businesses/{business_id}/categories")
    assert "Scaffolding" not in [row["name"] for row in listed.json()]
    # The soft-deleted category is also not fetchable by id.
    assert client.get(f"/businesses/{business_id}/categories/{payload['id']}").status_code == 404


def test_post_category_is_idempotent_by_client_uuid(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _category_payload(name="Plumbing")

    first = client.post(f"/businesses/{business_id}/categories", json=payload)
    second = client.post(f"/businesses/{business_id}/categories", json=payload)
    assert first.status_code == second.status_code == 200
    assert first.json()["id"] == second.json()["id"]

    matches = [row for row in client.get(f"/businesses/{business_id}/categories").json()
               if row["id"] == payload["id"]]
    assert len(matches) == 1


def test_post_category_same_uuid_cross_business_or_deleted_returns_404(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client, "One")
    other_business_id = _create_business(client, "Two")
    payload = _category_payload(name="Roofing")
    client.post(f"/businesses/{business_id}/categories", json=payload)

    cross_business = client.post(f"/businesses/{other_business_id}/categories", json=payload)
    assert cross_business.status_code == 404

    client.delete(f"/businesses/{business_id}/categories/{payload['id']}")
    deleted = client.post(f"/businesses/{business_id}/categories", json=payload)
    assert deleted.status_code == 404


def test_patch_category_rejects_stale_expected_updated_at(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _category_payload()
    created = client.post(f"/businesses/{business_id}/categories", json=payload).json()
    original_updated_at = created["updated_at"]

    matching = client.patch(
        f"/businesses/{business_id}/categories/{payload['id']}",
        json={"name": "Sand Delivery v2", "expected_updated_at": original_updated_at},
    )
    assert matching.status_code == 200

    rejected = client.patch(
        f"/businesses/{business_id}/categories/{payload['id']}",
        json={"name": "Stale", "expected_updated_at": "1999-01-01T00:00:00+00:00"},
    )
    assert rejected.status_code == 409


def test_update_category_rejects_explicit_null_name(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _category_payload()
    client.post(f"/businesses/{business_id}/categories", json=payload)

    resp = client.patch(
        f"/businesses/{business_id}/categories/{payload['id']}",
        json={"name": None},
    )
    assert resp.status_code == 422, resp.text


def test_non_member_gets_404_for_categories(client_for):
    alice = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(alice)

    bob = client_for(uuid.uuid4(), "bob@example.com")
    resp = bob.get(f"/businesses/{business_id}/categories")
    assert resp.status_code == 404


def test_list_categories_supports_search(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    client.post(
        f"/businesses/{business_id}/categories",
        json=_category_payload(name="Generator hire"),
    )

    search = client.get(f"/businesses/{business_id}/categories?search=generator")
    assert search.status_code == 200
    assert "Generator hire" in [row["name"] for row in search.json()]
