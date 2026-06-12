"""Phase 2 happy-path test.

Bypasses Supabase JWT verification by overriding the `get_current_user`
dependency. Exercises:

- Auto-profile creation behavior (we inject the Profile directly, matching
  what the real dep would return on first authenticated call).
- POST /businesses creates the business AND a 'owner' business_members row
  in the same transaction.
- GET /businesses returns it.
- Idempotent POST: re-posting with the same id returns the existing row.
- Cross-user access returns 404 (no leakage).

Uses SQLite in-memory for isolation; the SQLAlchemy models are dialect-agnostic.
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
from app.db.models import BusinessMember, Profile
from app.main import app

# SQLAlchemy 2.0's generic Uuid stores as CHAR(32) on SQLite. StaticPool keeps a
# single connection so all sessions see the same :memory: database (the default
# QueuePool would give each session its own empty DB).
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
def client_for(monkeypatch):
    """Build a TestClient with auth + db overridden to a specific user."""

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


def test_create_business_creates_owner_membership(client_for):
    user_id = uuid.uuid4()
    client = client_for(user_id, "alice@example.com")
    biz_id = uuid.uuid4()

    resp = client.post(
        "/businesses",
        json={"id": str(biz_id), "name": "Alice Construction"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["id"] == str(biz_id)
    assert body["owner_id"] == str(user_id)
    assert body["name"] == "Alice Construction"

    # Owner membership row exists.
    db = TestingSessionLocal()
    member = (
        db.query(BusinessMember)
        .filter_by(business_id=biz_id, user_id=user_id)
        .one()
    )
    assert member.role == "owner"
    db.close()


def test_post_business_is_idempotent(client_for):
    user_id = uuid.uuid4()
    client = client_for(user_id, "alice@example.com")
    biz_id = uuid.uuid4()
    payload = {"id": str(biz_id), "name": "Alice Construction"}

    first = client.post("/businesses", json=payload)
    second = client.post("/businesses", json=payload)
    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json()["id"] == second.json()["id"]

    # Only one membership row.
    db = TestingSessionLocal()
    count = db.query(BusinessMember).filter_by(business_id=biz_id).count()
    assert count == 1
    db.close()


def test_list_businesses_returns_only_mine(client_for):
    alice_id = uuid.uuid4()
    alice = client_for(alice_id, "alice@example.com")
    alice.post(
        "/businesses",
        json={"id": str(uuid.uuid4()), "name": "Alice Co"},
    )

    bob_id = uuid.uuid4()
    bob = client_for(bob_id, "bob@example.com")
    bob_biz_id = uuid.uuid4()
    bob.post("/businesses", json={"id": str(bob_biz_id), "name": "Bob Co"})

    bob_list = bob.get("/businesses").json()
    assert len(bob_list) == 1
    assert bob_list[0]["name"] == "Bob Co"


def test_other_users_business_returns_404(client_for):
    alice_id = uuid.uuid4()
    alice = client_for(alice_id, "alice@example.com")
    alice_biz_id = uuid.uuid4()
    alice.post("/businesses", json={"id": str(alice_biz_id), "name": "Alice Co"})

    bob_id = uuid.uuid4()
    bob = client_for(bob_id, "bob@example.com")
    resp = bob.get(f"/businesses/{alice_biz_id}")
    assert resp.status_code == 404


def test_member_can_get_business_but_cannot_patch(client_for):
    alice_id = uuid.uuid4()
    alice = client_for(alice_id, "alice@example.com")
    business_id = uuid.uuid4()
    alice.post("/businesses", json={"id": str(business_id), "name": "Alice Co"})

    bob_id = uuid.uuid4()
    bob = client_for(bob_id, "bob@example.com")

    db = TestingSessionLocal()
    db.add(BusinessMember(business_id=business_id, user_id=bob_id, role="member"))
    db.commit()
    db.close()

    get_resp = bob.get(f"/businesses/{business_id}")
    assert get_resp.status_code == 200

    patch_resp = bob.patch(f"/businesses/{business_id}", json={"name": "Renamed"})
    assert patch_resp.status_code == 403
    assert patch_resp.json()["detail"] == "Business owner access required."


def test_get_me_and_patch_me(client_for):
    user_id = uuid.uuid4()
    client = client_for(user_id, "alice@example.com")

    me = client.get("/me").json()
    assert me["id"] == str(user_id)
    assert me["email"] == "alice@example.com"
    assert me["full_name"] is None

    resp = client.patch("/me", json={"full_name": "Alice A."})
    assert resp.status_code == 200
    assert resp.json()["full_name"] == "Alice A."


def test_patch_me_blank_phone_clears(client_for):
    user_id = uuid.uuid4()
    client = client_for(user_id, "alice@example.com")

    resp = client.patch("/me", json={"phone": "+2348012345678"})
    assert resp.status_code == 200
    assert resp.json()["phone"] == "+2348012345678"

    # Omitting the key keeps the stored phone (exclude_unset semantics).
    resp = client.patch("/me", json={"full_name": "Alice A."})
    assert resp.status_code == 200
    assert resp.json()["phone"] == "+2348012345678"

    # A blank phone is the wire form of "clear" and lands as null, not "".
    resp = client.patch("/me", json={"phone": ""})
    assert resp.status_code == 200
    assert resp.json()["phone"] is None
