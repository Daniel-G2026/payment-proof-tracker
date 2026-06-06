"""Phase 13 Cut 5 — project-tagged money-out + the Land/site cost seed.

Covers persisting/returning project_id on money-out, clearing it, rejecting a
project from another business, and that the "Land / site cost" default category
is seeded for new businesses (and is idempotent via its deterministic id).
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
from app.db.models.category import (
    DEFAULT_CONSTRUCTION_CATEGORIES,
    _seed_category_id,
    default_category_seed,
    normalize_category_name,
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


def _setup(client: TestClient) -> tuple[uuid.UUID, uuid.UUID]:
    business_id = uuid.uuid4()
    client.post("/businesses", json={"id": str(business_id), "name": "Estate Co"})
    project_id = uuid.uuid4()
    client.post(
        f"/businesses/{business_id}/projects",
        json={"id": str(project_id), "name": "Sunrise"},
    )
    return business_id, project_id


def _money_out(project_id=None) -> dict:
    body = {
        "id": str(uuid.uuid4()),
        "amount_minor": 15000000,
        "currency": "NGN",
        "direction": "money_out",
        "transaction_date": "2026-06-01",
        "category": "Materials",
    }
    if project_id is not None:
        body["project_id"] = str(project_id)
    return body


# --------------------------------------------------------------------------- #
# Land / site cost seed
# --------------------------------------------------------------------------- #


def test_land_site_cost_is_a_default_category():
    assert "Land / site cost" in DEFAULT_CONSTRUCTION_CATEGORIES


def test_new_business_is_seeded_with_land_site_cost(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = uuid.uuid4()
    client.post("/businesses", json={"id": str(business_id), "name": "Estate Co"})
    names = [row["name"] for row in client.get(f"/businesses/{business_id}/categories").json()]
    assert "Land / site cost" in names


def test_land_seed_id_is_deterministic_and_idempotent():
    # The backfill relies on a deterministic per-business id so re-running can't
    # duplicate the create-business seed.
    business_id = uuid.uuid4()
    normalized = normalize_category_name("Land / site cost")
    a = _seed_category_id(business_id, normalized)
    b = _seed_category_id(business_id, normalized)
    assert a == b
    seeded = {row["normalized_name"]: row["id"] for row in default_category_seed(business_id)}
    assert seeded[normalized] == a


# --------------------------------------------------------------------------- #
# Project-tagged money-out
# --------------------------------------------------------------------------- #


def test_money_out_tagged_with_project(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id, project_id = _setup(client)
    body = _money_out(project_id=project_id)
    created = client.post(f"/businesses/{business_id}/transactions", json=body)
    assert created.status_code == 200, created.text
    assert created.json()["project_id"] == str(project_id)

    got = client.get(f"/businesses/{business_id}/transactions/{body['id']}").json()
    assert got["project_id"] == str(project_id)


def test_money_out_project_can_be_cleared(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id, project_id = _setup(client)
    body = _money_out(project_id=project_id)
    client.post(f"/businesses/{business_id}/transactions", json=body)
    cleared = client.patch(
        f"/businesses/{business_id}/transactions/{body['id']}",
        json={"project_id": None},
    )
    assert cleared.status_code == 200
    assert cleared.json()["project_id"] is None


def test_create_rejects_project_from_other_business(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_a, _ = _setup(client)
    _, project_b = _setup(client)  # second business + its project
    created = client.post(
        f"/businesses/{business_a}/transactions",
        json=_money_out(project_id=project_b),
    )
    assert created.status_code == 422
