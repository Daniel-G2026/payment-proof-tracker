"""Phase 13 Cut 1 — projects + units CRUD, auth isolation, idempotency.

Mirrors the category test harness (in-memory SQLite, dependency overrides).
Covers create/list/get/patch/soft-delete for both tables plus the Cut 1
hardening edges: cross-business isolation, idempotent client-id create,
optimistic-concurrency 409, buyer/project membership validation, and the
units `project_id` list filter.
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


def _create_business(client: TestClient, name: str = "Estate Co") -> uuid.UUID:
    business_id = uuid.uuid4()
    resp = client.post("/businesses", json={"id": str(business_id), "name": name})
    assert resp.status_code == 200, resp.text
    return business_id


def _create_project(client: TestClient, business_id: uuid.UUID, name: str = "Sunrise Estate"):
    project_id = uuid.uuid4()
    resp = client.post(
        f"/businesses/{business_id}/projects",
        json={"id": str(project_id), "name": name},
    )
    assert resp.status_code == 200, resp.text
    return project_id, resp.json()


def _create_buyer(client: TestClient, business_id: uuid.UUID, name: str = "Ada Buyer") -> uuid.UUID:
    cp_id = uuid.uuid4()
    resp = client.post(
        f"/businesses/{business_id}/counterparties",
        json={"id": str(cp_id), "name": name, "type": "buyer"},
    )
    assert resp.status_code == 200, resp.text
    return cp_id


# --------------------------------------------------------------------------- #
# Projects
# --------------------------------------------------------------------------- #


def test_project_create_list_get_patch_soft_delete(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    project_id, body = _create_project(client, business_id)

    assert body["business_id"] == str(business_id)
    assert body["name"] == "Sunrise Estate"
    assert body["note"] is None

    listed = client.get(f"/businesses/{business_id}/projects")
    assert listed.status_code == 200
    assert str(project_id) in [row["id"] for row in listed.json()]

    got = client.get(f"/businesses/{business_id}/projects/{project_id}")
    assert got.status_code == 200

    patched = client.patch(
        f"/businesses/{business_id}/projects/{project_id}",
        json={"name": "Sunrise Estate Phase 2", "note": "  east block  "},
    )
    assert patched.status_code == 200, patched.text
    assert patched.json()["name"] == "Sunrise Estate Phase 2"
    assert patched.json()["note"] == "east block"

    deleted = client.delete(f"/businesses/{business_id}/projects/{project_id}")
    assert deleted.status_code == 204
    after = client.get(f"/businesses/{business_id}/projects")
    assert str(project_id) not in [row["id"] for row in after.json()]
    assert client.get(f"/businesses/{business_id}/projects/{project_id}").status_code == 404


def test_project_create_is_idempotent_on_client_id(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    project_id = uuid.uuid4()
    payload = {"id": str(project_id), "name": "Once"}

    first = client.post(f"/businesses/{business_id}/projects", json=payload)
    second = client.post(f"/businesses/{business_id}/projects", json={**payload, "name": "Twice"})
    assert first.status_code == 200
    assert second.status_code == 200
    # Re-POST of the same id returns the existing row, not a duplicate.
    assert second.json()["id"] == str(project_id)
    assert second.json()["name"] == "Once"
    listed = client.get(f"/businesses/{business_id}/projects")
    assert [row["id"] for row in listed.json()].count(str(project_id)) == 1


def test_project_patch_optimistic_concurrency_conflict(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    project_id, body = _create_project(client, business_id)
    stale = client.patch(
        f"/businesses/{business_id}/projects/{project_id}",
        json={"name": "Nope", "expected_updated_at": "2000-01-01T00:00:00+00:00"},
    )
    assert stale.status_code == 409


def test_project_isolated_across_businesses(client_for):
    # client_for sets a *global* current-user override, so finish all of Alice's
    # setup before building Bob (the last-built client wins).
    alice = client_for(uuid.uuid4(), "alice@example.com")
    alice_business = _create_business(alice, "Alice Estate")
    project_id, _ = _create_project(alice, alice_business)

    bob = client_for(uuid.uuid4(), "bob@example.com")
    bob_business = _create_business(bob, "Bob Estate")
    # Bob cannot see or reach Alice's project, even via his own business path.
    assert bob.get(f"/businesses/{alice_business}/projects").status_code == 404
    assert bob.get(f"/businesses/{bob_business}/projects/{project_id}").status_code == 404


# --------------------------------------------------------------------------- #
# Units
# --------------------------------------------------------------------------- #


def test_unit_create_with_buyer_list_patch_soft_delete(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    project_id, _ = _create_project(client, business_id)
    buyer_id = _create_buyer(client, business_id)

    unit_id = uuid.uuid4()
    created = client.post(
        f"/businesses/{business_id}/units",
        json={
            "id": str(unit_id),
            "project_id": str(project_id),
            "label": "Plot 12",
            "buyer_counterparty_id": str(buyer_id),
            "total_price_minor": 200000000,
            "currency": "NGN",
        },
    )
    assert created.status_code == 200, created.text
    body = created.json()
    assert body["project_id"] == str(project_id)
    assert body["buyer_counterparty_id"] == str(buyer_id)
    assert body["total_price_minor"] == 200000000
    assert body["status"] == "available"  # default applied

    listed = client.get(f"/businesses/{business_id}/units")
    assert str(unit_id) in [row["id"] for row in listed.json()]

    # Filter by project.
    units_url = f"/businesses/{business_id}/units"
    filtered = client.get(units_url, params={"project_id": str(project_id)})
    assert [row["id"] for row in filtered.json()] == [str(unit_id)]
    other_project = uuid.uuid4()
    empty = client.get(units_url, params={"project_id": str(other_project)})
    assert empty.json() == []

    patched = client.patch(
        f"/businesses/{business_id}/units/{unit_id}",
        json={"label": "Plot 12A", "status": "sold", "buyer_counterparty_id": None},
    )
    assert patched.status_code == 200, patched.text
    assert patched.json()["label"] == "Plot 12A"
    assert patched.json()["status"] == "sold"
    assert patched.json()["buyer_counterparty_id"] is None  # buyer cleared

    deleted = client.delete(f"/businesses/{business_id}/units/{unit_id}")
    assert deleted.status_code == 204
    remaining = [row["id"] for row in client.get(units_url).json()]
    assert str(unit_id) not in remaining


def test_unit_create_without_buyer_defaults(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    project_id, _ = _create_project(client, business_id)
    unit_id = uuid.uuid4()
    created = client.post(
        f"/businesses/{business_id}/units",
        json={"id": str(unit_id), "project_id": str(project_id), "label": "Plot 1"},
    )
    assert created.status_code == 200, created.text
    body = created.json()
    assert body["buyer_counterparty_id"] is None
    assert body["total_price_minor"] is None
    assert body["currency"] == "NGN"
    assert body["status"] == "available"


def test_unit_create_rejects_unknown_project(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    created = client.post(
        f"/businesses/{business_id}/units",
        json={"id": str(uuid.uuid4()), "project_id": str(uuid.uuid4()), "label": "Plot 1"},
    )
    assert created.status_code == 404


def test_unit_create_rejects_buyer_from_other_business(client_for):
    # One owner, two businesses: a buyer from business B must not attach to a
    # unit in business A.
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_a = _create_business(client, "Estate A")
    project_a, _ = _create_project(client, business_a)
    business_b = _create_business(client, "Estate B")
    buyer_b = _create_buyer(client, business_b)

    created = client.post(
        f"/businesses/{business_a}/units",
        json={
            "id": str(uuid.uuid4()),
            "project_id": str(project_a),
            "label": "Plot X",
            "buyer_counterparty_id": str(buyer_b),
        },
    )
    assert created.status_code == 404


def test_unit_rejects_unsupported_currency(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    project_id, _ = _create_project(client, business_id)
    created = client.post(
        f"/businesses/{business_id}/units",
        json={
            "id": str(uuid.uuid4()),
            "project_id": str(project_id),
            "label": "Plot 1",
            "currency": "<script>",
        },
    )
    assert created.status_code == 422


def test_unit_isolated_across_businesses(client_for):
    alice = client_for(uuid.uuid4(), "alice@example.com")
    alice_business = _create_business(alice, "Alice Estate")
    alice_project, _ = _create_project(alice, alice_business)
    unit_id = uuid.uuid4()
    alice.post(
        f"/businesses/{alice_business}/units",
        json={"id": str(unit_id), "project_id": str(alice_project), "label": "Plot 1"},
    )

    bob = client_for(uuid.uuid4(), "bob@example.com")
    bob_business = _create_business(bob, "Bob Estate")
    assert bob.get(f"/businesses/{alice_business}/units").status_code == 404
    assert bob.get(f"/businesses/{bob_business}/units/{unit_id}").status_code == 404
