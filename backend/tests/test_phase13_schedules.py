"""Phase 13 Cut 2 — schedule version CRUD + one-active-per-unit enforcement.

Covers manual schedule entry, renegotiation (new active version + old kept),
the single-active invariant, idempotent create, and auth/unit guards.
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


def _setup_unit(client: TestClient) -> tuple[uuid.UUID, uuid.UUID]:
    business_id = uuid.uuid4()
    biz = client.post("/businesses", json={"id": str(business_id), "name": "Estate Co"})
    assert biz.status_code == 200
    project_id = uuid.uuid4()
    assert client.post(
        f"/businesses/{business_id}/projects", json={"id": str(project_id), "name": "Sunrise"}
    ).status_code == 200
    unit_id = uuid.uuid4()
    assert client.post(
        f"/businesses/{business_id}/units",
        json={"id": str(unit_id), "project_id": str(project_id), "label": "Plot 12"},
    ).status_code == 200
    return business_id, unit_id


def _schedule_payload(*installments: tuple[int, int, str]) -> dict:
    return {
        "id": str(uuid.uuid4()),
        "source": "manual",
        "installments": [
            {"id": str(uuid.uuid4()), "sequence": seq, "amount_minor": amount, "due_date": due}
            for (seq, amount, due) in installments
        ],
    }


def _versions_url(business_id, unit_id) -> str:
    return f"/businesses/{business_id}/units/{unit_id}/schedule-versions"


def test_clear_unit_buyer_via_explicit_null(client_for):
    """A PATCH carrying buyer_counterparty_id: null removes the buyer; omitting
    the key leaves it unchanged. (The Android client serializes the null
    explicitly for unit updates so a removed buyer survives a fresh-device
    restore.)"""
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id, unit_id = _setup_unit(client)

    buyer_id = uuid.uuid4()
    assert client.post(
        f"/businesses/{business_id}/counterparties",
        json={"id": str(buyer_id), "name": "Chioma B.", "type": "customer"},
    ).status_code == 200
    assert client.patch(
        f"/businesses/{business_id}/units/{unit_id}",
        json={"buyer_counterparty_id": str(buyer_id)},
    ).json()["buyer_counterparty_id"] == str(buyer_id)

    # Omitting the key keeps the buyer.
    kept = client.patch(
        f"/businesses/{business_id}/units/{unit_id}", json={"label": "Plot 12A"}
    )
    assert kept.json()["buyer_counterparty_id"] == str(buyer_id)

    # Explicit null clears it.
    cleared = client.patch(
        f"/businesses/{business_id}/units/{unit_id}",
        json={"buyer_counterparty_id": None},
    )
    assert cleared.status_code == 200, cleared.text
    assert cleared.json()["buyer_counterparty_id"] is None


def test_create_schedule_version_with_installments(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id, unit_id = _setup_unit(client)
    payload = _schedule_payload(
        (1, 50000000, "2026-01-31"),
        (2, 50000000, "2026-02-28"),
    )

    created = client.post(_versions_url(business_id, unit_id), json=payload)
    assert created.status_code == 200, created.text
    body = created.json()
    assert body["version_no"] == 1
    assert body["is_active"] is True
    assert [i["sequence"] for i in body["installments"]] == [1, 2]
    assert body["installments"][0]["amount_minor"] == 50000000


def test_renegotiation_creates_new_active_version_and_keeps_old(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id, unit_id = _setup_unit(client)
    url = _versions_url(business_id, unit_id)

    v1 = client.post(url, json=_schedule_payload((1, 50000000, "2026-01-31"))).json()
    v2 = client.post(
        url,
        json=_schedule_payload((1, 40000000, "2026-02-28"), (2, 80000000, "2026-04-30")),
    ).json()

    assert v1["version_no"] == 1
    assert v2["version_no"] == 2
    assert v2["is_active"] is True

    listed = client.get(url).json()
    assert [v["version_no"] for v in listed] == [1, 2]
    # Exactly one active version, and it is v2.
    active_versions = [v for v in listed if v["is_active"]]
    assert len(active_versions) == 1
    assert active_versions[0]["id"] == v2["id"]

    active = client.get(f"{url}/active").json()
    assert active["id"] == v2["id"]
    assert [i["sequence"] for i in active["installments"]] == [1, 2]


def test_create_is_idempotent_on_client_id(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id, unit_id = _setup_unit(client)
    url = _versions_url(business_id, unit_id)
    payload = _schedule_payload((1, 50000000, "2026-01-31"))

    first = client.post(url, json=payload)
    second = client.post(url, json=payload)
    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json()["id"] == second.json()["id"]
    # Re-POST did not create a second version.
    assert len(client.get(url).json()) == 1


def test_get_active_404_when_no_schedule(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id, unit_id = _setup_unit(client)
    assert client.get(f"{_versions_url(business_id, unit_id)}/active").status_code == 404


def test_schedule_rejects_empty_installments(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id, unit_id = _setup_unit(client)
    payload = {"id": str(uuid.uuid4()), "source": "manual", "installments": []}
    assert client.post(_versions_url(business_id, unit_id), json=payload).status_code == 422


def test_schedule_unknown_unit_404(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id, _ = _setup_unit(client)
    bogus_unit = uuid.uuid4()
    payload = _schedule_payload((1, 50000000, "2026-01-31"))
    assert client.post(_versions_url(business_id, bogus_unit), json=payload).status_code == 404


def test_schedule_isolated_across_businesses(client_for):
    alice = client_for(uuid.uuid4(), "alice@example.com")
    alice_business, alice_unit = _setup_unit(alice)
    alice.post(
        _versions_url(alice_business, alice_unit),
        json=_schedule_payload((1, 50000000, "2026-01-31")),
    )

    bob = client_for(uuid.uuid4(), "bob@example.com")
    assert bob.get(_versions_url(alice_business, alice_unit)).status_code == 404
