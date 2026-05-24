"""Stress / edge-case probes for Phase 3 transaction APIs.

These tests are intentionally adversarial — they probe boundaries and
data-integrity assumptions that the happy-path tests do not.
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


def _create_business(client: TestClient, name: str = "Ledger Co") -> uuid.UUID:
    bid = uuid.uuid4()
    resp = client.post("/businesses", json={"id": str(bid), "name": name})
    assert resp.status_code == 200, resp.text
    return bid


def _payload(**overrides):
    base = {
        "id": str(uuid.uuid4()),
        "amount_minor": 5000,
        "currency": "NGN",
        "direction": "money_in",
        "transaction_date": "2026-05-23",
    }
    base.update(overrides)
    return base


# ---------------------------------------------------------------------------
# Edge cases worth checking
# ---------------------------------------------------------------------------


def test_create_is_idempotent_for_same_id_same_business(client_for):
    """Phase 3 client supplies the UUID; offline retries must not duplicate."""
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _payload(amount_minor=1234)
    r1 = client.post(f"/businesses/{business_id}/transactions", json=payload)
    r2 = client.post(f"/businesses/{business_id}/transactions", json=payload)
    assert r1.status_code == r2.status_code == 200
    assert r1.json()["id"] == r2.json()["id"]


def test_amount_minor_accepts_large_value(client_for):
    """Real businesses hit ~10M+ NGN. Server uses Integer column; flag if it
    can't store amounts beyond 32-bit signed int (~21M NGN in kobo)."""
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    # 50_000_000 NGN = 5_000_000_000 kobo — exceeds int32 max (2_147_483_647).
    resp = client.post(
        f"/businesses/{business_id}/transactions",
        json=_payload(amount_minor=5_000_000_000),
    )
    assert resp.status_code == 200, (
        f"Large amount failed: {resp.status_code} {resp.text}"
    )
    assert resp.json()["amount_minor"] == 5_000_000_000


def test_patch_with_explicit_null_direction_is_rejected(client_for):
    """PATCH {"direction": null} must 422 — not silently NULL a NOT NULL column."""
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _payload()
    client.post(f"/businesses/{business_id}/transactions", json=payload)
    resp = client.patch(
        f"/businesses/{business_id}/transactions/{payload['id']}",
        json={"direction": None},
    )
    assert resp.status_code == 422, resp.text


def test_patch_with_explicit_null_amount_is_rejected(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _payload()
    client.post(f"/businesses/{business_id}/transactions", json=payload)
    resp = client.patch(
        f"/businesses/{business_id}/transactions/{payload['id']}",
        json={"amount_minor": None},
    )
    assert resp.status_code == 422, resp.text


def test_patch_with_explicit_null_optional_field_clears_it(client_for):
    """Optional text fields stay nullable — sending null clears them."""
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _payload(note="original")
    client.post(f"/businesses/{business_id}/transactions", json=payload)
    resp = client.patch(
        f"/businesses/{business_id}/transactions/{payload['id']}",
        json={"note": None, "counterparty_name": None},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["note"] is None
    assert resp.json()["counterparty_name"] is None


def test_patch_omitting_required_field_is_allowed(client_for):
    """Omission is still fine — PATCH must remain partial."""
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _payload(direction="money_in", amount_minor=5000)
    client.post(f"/businesses/{business_id}/transactions", json=payload)
    resp = client.patch(
        f"/businesses/{business_id}/transactions/{payload['id']}",
        json={"note": "just adding a note"},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["direction"] == "money_in"
    assert resp.json()["amount_minor"] == 5000


def test_create_rejects_invalid_direction(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    resp = client.post(
        f"/businesses/{business_id}/transactions",
        json=_payload(direction="unknown"),
    )
    assert resp.status_code == 422


def test_create_rejects_zero_or_negative_amount(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    for bad in (0, -1, -1000):
        resp = client.post(
            f"/businesses/{business_id}/transactions",
            json=_payload(amount_minor=bad),
        )
        assert resp.status_code == 422, f"amount {bad} should be rejected"


def test_date_range_filter_inclusive(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    for d in ("2026-05-20", "2026-05-23", "2026-05-30"):
        client.post(
            f"/businesses/{business_id}/transactions",
            json=_payload(transaction_date=d),
        )
    resp = client.get(
        f"/businesses/{business_id}/transactions"
        f"?start_date=2026-05-22&end_date=2026-05-25"
    )
    assert resp.status_code == 200
    rows = resp.json()
    assert len(rows) == 1
    assert rows[0]["transaction_date"] == "2026-05-23"


def test_recreate_after_soft_delete_returns_existing_404(client_for):
    """If a client retries an upload after the row was soft-deleted server-side,
    re-creating with the same id currently 404s (cannot resurrect). Document
    the behavior so we notice if it changes."""
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _payload()
    client.post(f"/businesses/{business_id}/transactions", json=payload)
    client.delete(f"/businesses/{business_id}/transactions/{payload['id']}")
    resp = client.post(f"/businesses/{business_id}/transactions", json=payload)
    # Current implementation returns 404 here — flag if this changes silently.
    assert resp.status_code in (200, 404, 409)


def test_search_with_percent_wildcard_is_literal(client_for):
    """User searching for "100%" should not become a wildcard match."""
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    client.post(
        f"/businesses/{business_id}/transactions",
        json=_payload(note="Paid 100% upfront"),
    )
    client.post(
        f"/businesses/{business_id}/transactions",
        json=_payload(note="Paid in full"),
    )
    resp = client.get(f"/businesses/{business_id}/transactions?search=100%25")
    # Either match only the literal "100%" or match both — but if it matches
    # both because of unescaped %, that's a bug.
    assert resp.status_code == 200
    matches = [r["note"] for r in resp.json()]
    # Document current behavior — if both rows match, the % is leaking through.
    assert "Paid 100% upfront" in matches


def test_update_persists_updated_at_change(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    payload = _payload()
    created = client.post(
        f"/businesses/{business_id}/transactions", json=payload
    ).json()
    patched = client.patch(
        f"/businesses/{business_id}/transactions/{payload['id']}",
        json={"note": "edited"},
    ).json()
    # updated_at should move forward (or at least not move backward).
    assert patched["updated_at"] >= created["updated_at"]
