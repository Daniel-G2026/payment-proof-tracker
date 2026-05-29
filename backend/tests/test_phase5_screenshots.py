from __future__ import annotations

import time
import uuid
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.api.deps import get_current_user, get_db
from app.core.config import settings
from app.db.base import Base
from app.db.models import BusinessMember, Profile
from app.main import app
from app.services.proof_storage import ProofStorage, sign_screenshot_url, signed_url_secret

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
TestingSessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)

# Minimum bytes that satisfy the route's JPEG magic-byte sniff. Prepended to
# fixture content so the upload reaches transaction/business validation
# instead of getting 415'd at the image check.
JPEG_MAGIC = b"\xff\xd8\xff\xe0"
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


@pytest.fixture(autouse=True)
def setup_db(tmp_path):
    settings.proof_storage_backend = "local"
    settings.proof_storage_local_dir = str(tmp_path / "proofs")
    original_environment = settings.environment
    settings.environment = "development"
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)
    settings.environment = original_environment


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


def _create_business(client: TestClient) -> uuid.UUID:
    business_id = uuid.uuid4()
    resp = client.post("/businesses", json={"id": str(business_id), "name": "Ledger Co"})
    assert resp.status_code == 200, resp.text
    return business_id


def _create_transaction(client: TestClient, business_id: uuid.UUID) -> uuid.UUID:
    transaction_id = uuid.uuid4()
    resp = client.post(
        f"/businesses/{business_id}/transactions",
        json={
            "id": str(transaction_id),
            "amount_minor": 125000,
            "currency": "NGN",
            "direction": "money_in",
            "transaction_date": "2026-05-24",
            "counterparty_name": "Adewale Supplies",
        },
    )
    assert resp.status_code == 200, resp.text
    return transaction_id


def _upload(
    client: TestClient,
    business_id: uuid.UUID,
    transaction_id: uuid.UUID | str,
    content: bytes,
    screenshot_id: uuid.UUID | None = None,
    mime_type: str = "image/jpeg",
    prepend_magic: bool = True,
):
    """Helper. By default prepends JPEG magic bytes so the route's image
    sniff passes; callers testing the sniff itself, or that need precise body
    lengths (the size-cap tests), pass `prepend_magic=False`.
    """
    body = (JPEG_MAGIC + content) if prepend_magic and mime_type == "image/jpeg" else content
    data = {
        "transaction_id": str(transaction_id),
        "original_file_size": str(len(body)),
        "width": "1080",
        "height": "1920",
    }
    if screenshot_id is not None:
        data["id"] = str(screenshot_id)
    return client.post(
        f"/businesses/{business_id}/screenshots",
        data=data,
        files={"file": ("proof.jpg", body, mime_type)},
    )


def test_member_can_upload_screenshot_and_get_signed_content(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)

    payload = b"fake-image-bytes"
    created = _upload(client, business_id, transaction_id, payload)
    assert created.status_code == 200, created.text
    body = created.json()
    assert body["business_id"] == str(business_id)
    assert body["transaction_id"] == str(transaction_id)
    assert body["uploaded_file_size"] == len(JPEG_MAGIC + payload)
    assert body["storage_status"] == "stored"

    signed = client.get(f"/screenshots/{body['id']}/signed-url")
    assert signed.status_code == 200, signed.text

    content = client.get(signed.json()["url"])
    assert content.status_code == 200, content.text
    assert content.content == JPEG_MAGIC + payload


def test_non_member_cannot_read_screenshot(client_for):
    alice = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(alice)
    transaction_id = _create_transaction(alice, business_id)
    screenshot_id = _upload(alice, business_id, transaction_id, b"proof").json()["id"]

    bob = client_for(uuid.uuid4(), "bob@example.com")
    resp = bob.get(f"/screenshots/{screenshot_id}")
    assert resp.status_code == 404


def test_upload_rejects_cross_business_transaction(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    other_business_id = _create_business(client)
    transaction_id = _create_transaction(client, other_business_id)

    resp = _upload(client, business_id, transaction_id, b"proof")
    assert resp.status_code == 404


def test_upload_rejects_oversized_image(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)

    # Raw bytes (not a real JPEG). The 413 size check fires before the magic
    # byte sniff, so the route rejects on size regardless of content shape.
    resp = _upload(
        client, business_id, transaction_id,
        b"x" * (5 * 1024 * 1024 + 1),
        prepend_magic=False,
    )
    assert resp.status_code == 413


def test_member_role_can_upload_screenshot(client_for):
    alice = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(alice)
    transaction_id = _create_transaction(alice, business_id)

    bob_id = uuid.uuid4()
    bob = client_for(bob_id, "bob@example.com")
    db = TestingSessionLocal()
    db.add(BusinessMember(business_id=business_id, user_id=bob_id, role="member"))
    db.commit()
    db.close()

    resp = _upload(bob, business_id, transaction_id, b"proof")
    assert resp.status_code == 200, resp.text


def test_upload_is_idempotent_by_client_screenshot_uuid(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)
    screenshot_id = uuid.uuid4()

    first = _upload(client, business_id, transaction_id, b"first", screenshot_id=screenshot_id)
    second = _upload(client, business_id, transaction_id, b"second", screenshot_id=screenshot_id)

    assert first.status_code == second.status_code == 200
    assert first.json()["id"] == second.json()["id"] == str(screenshot_id)
    signed = client.get(f"/screenshots/{screenshot_id}/signed-url")
    content = client.get(signed.json()["url"])
    # Idempotent path returns the first upload's bytes — second never overwrote.
    assert content.content == JPEG_MAGIC + b"first"


def test_upload_accepts_exactly_five_megabytes(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)

    # Build exactly-5MB content that begins with valid JPEG magic so it
    # survives both the size cap and the image-payload check.
    content = JPEG_MAGIC + b"x" * (5 * 1024 * 1024 - len(JPEG_MAGIC))
    resp = _upload(client, business_id, transaction_id, content, prepend_magic=False)

    assert resp.status_code == 200, resp.text
    assert resp.json()["uploaded_file_size"] == len(content)


def test_upload_rejects_non_image_and_malformed_fields(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)

    non_image = _upload(client, business_id, transaction_id, b"not-image", mime_type="text/plain")
    assert non_image.status_code == 415

    bad_transaction = _upload(client, business_id, "not-a-uuid", b"proof")
    assert bad_transaction.status_code == 422

    # Use real JPEG-magic bytes so the magic-byte sniff passes and we reach
    # the field-parsing 422 instead of getting an early 415 on the payload.
    bad_width = client.post(
        f"/businesses/{business_id}/screenshots",
        data={
            "transaction_id": str(transaction_id),
            "original_file_size": "123",
            "width": "wide",
        },
        files={"file": ("proof.jpg", JPEG_MAGIC + b"proof", "image/jpeg")},
    )
    assert bad_width.status_code == 422


def test_signed_content_rejects_tampered_and_expired_links(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)
    screenshot_id = _upload(client, business_id, transaction_id, b"proof").json()["id"]

    signed = client.get(f"/screenshots/{screenshot_id}/signed-url").json()["url"]
    parsed = urlparse(signed)
    query = parse_qs(parsed.query)
    tampered = (
        f"{parsed.path}?expires={query['expires'][0]}&signature={query['signature'][0][::-1]}"
    )
    assert client.get(tampered).status_code == 403

    expired_at = int(time.time()) - 5
    expired_signature = sign_screenshot_url(uuid.UUID(screenshot_id), expired_at)
    expired = f"{parsed.path}?expires={expired_at}&signature={expired_signature}"
    assert client.get(expired).status_code == 403


def test_bulk_screenshot_uploads_list_and_store_consistently(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)
    uploaded_ids: list[str] = []

    for index in range(75):
        content = f"proof-{index}".encode() * 1024
        resp = _upload(client, business_id, transaction_id, content)
        assert resp.status_code == 200, resp.text
        uploaded_ids.append(resp.json()["id"])

    listed = client.get(f"/businesses/{business_id}/screenshots?limit=200")
    assert listed.status_code == 200
    listed_ids = {row["id"] for row in listed.json()}
    assert set(uploaded_ids) <= listed_ids

    storage_root = Path(settings.proof_storage_local_dir)
    stored_files = list((storage_root / str(business_id)).glob("*.jpg"))
    assert len(stored_files) == 75


def test_oversized_content_length_rejected_without_buffering(client_for):
    # Server should refuse based on the declared Content-Length header before
    # buffering the full body. Httpx will still send the body, but our route
    # raises 413 the moment it inspects the header.
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)

    huge = b"x" * (20 * 1024 * 1024)
    resp = _upload(client, business_id, transaction_id, huge, prepend_magic=False)
    assert resp.status_code == 413


def test_delete_screenshot_soft_deletes_and_hides_from_list(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)
    screenshot_id = _upload(client, business_id, transaction_id, b"proof").json()["id"]
    storage_path = Path(settings.proof_storage_local_dir) / str(business_id) / f"{screenshot_id}.jpg"
    assert storage_path.exists()

    resp = client.delete(f"/screenshots/{screenshot_id}")
    assert resp.status_code == 204
    assert not storage_path.exists()
    assert client.get(f"/screenshots/{screenshot_id}").status_code == 404

    listed = client.get(f"/businesses/{business_id}/screenshots")
    assert listed.status_code == 200
    assert all(row["id"] != screenshot_id for row in listed.json())


def test_delete_screenshot_is_idempotent_for_owner(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)
    screenshot_id = _upload(client, business_id, transaction_id, b"proof").json()["id"]

    first = client.delete(f"/screenshots/{screenshot_id}")
    second = client.delete(f"/screenshots/{screenshot_id}")

    assert first.status_code == 204
    assert second.status_code == 204


def test_delete_screenshot_rejects_non_member(client_for):
    alice = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(alice)
    transaction_id = _create_transaction(alice, business_id)
    screenshot_id = _upload(alice, business_id, transaction_id, b"proof").json()["id"]

    bob = client_for(uuid.uuid4(), "bob@example.com")
    assert bob.delete(f"/screenshots/{screenshot_id}").status_code == 404


def test_list_screenshots_supports_pagination(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)
    for index in range(5):
        resp = _upload(client, business_id, transaction_id, f"p{index}".encode())
        assert resp.status_code == 200

    page = client.get(f"/businesses/{business_id}/screenshots?limit=2&offset=0").json()
    assert len(page) == 2
    next_page = client.get(f"/businesses/{business_id}/screenshots?limit=2&offset=2").json()
    assert len(next_page) == 2
    assert {row["id"] for row in page}.isdisjoint({row["id"] for row in next_page})

    bad = client.get(f"/businesses/{business_id}/screenshots?limit=500")
    assert bad.status_code == 422


def test_signed_url_secret_refuses_to_fall_back_in_production():
    original_environment = settings.environment
    original_secret = settings.proof_signed_url_secret
    original_jwt = settings.supabase_jwt_secret
    settings.environment = "production"
    settings.proof_signed_url_secret = ""
    settings.supabase_jwt_secret = ""
    try:
        with pytest.raises(HTTPException) as exc:
            signed_url_secret()
        assert exc.value.status_code == 500
    finally:
        settings.environment = original_environment
        settings.proof_signed_url_secret = original_secret
        settings.supabase_jwt_secret = original_jwt


def test_idempotent_upload_survives_integrity_race(client_for, monkeypatch):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)
    screenshot_id = uuid.uuid4()

    first = _upload(
        client, business_id, transaction_id, b"first", screenshot_id=screenshot_id
    )
    assert first.status_code == 200

    storage_root = Path(settings.proof_storage_local_dir) / str(business_id)
    files_before = {p.name for p in storage_root.glob("*.jpg")}
    assert len(files_before) == 1  # baseline

    # Force the upload route down the IntegrityError branch by hiding the
    # existing row from the idempotency check. The INSERT then collides with
    # the row already in the DB.
    from app.api.routes import screenshots as route
    from sqlalchemy.orm import Session as SASession

    real_session_get = SASession.get

    def fake_get(self, entity, ident, *args, **kwargs):
        if (
            entity is route.Screenshot
            and str(ident) == str(screenshot_id)
            and not getattr(fake_get, "called", False)
        ):
            fake_get.called = True
            return None
        return real_session_get(self, entity, ident, *args, **kwargs)

    monkeypatch.setattr(SASession, "get", fake_get)

    racing = _upload(
        client, business_id, transaction_id, b"second", screenshot_id=screenshot_id
    )
    assert racing.status_code == 200
    assert racing.json()["id"] == str(screenshot_id)

    files_after = {p.name for p in storage_root.glob("*.jpg")}
    # The storage key is deterministic per screenshot_id. The losing racer
    # wrote to the same key the winner already references, so the file must
    # still be there — the winner needs it.
    assert files_after == files_before


def test_upload_cleans_orphan_on_unexpected_commit_failure(client_for, monkeypatch):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)

    storage_root = Path(settings.proof_storage_local_dir) / str(business_id)
    storage_root.mkdir(parents=True, exist_ok=True)
    baseline = {p.name for p in storage_root.glob("*.jpg")}

    from sqlalchemy.orm import Session as SASession

    real_commit = SASession.commit

    def fake_commit(self):
        raise RuntimeError("simulated commit failure")

    monkeypatch.setattr(SASession, "commit", fake_commit)
    try:
        # TestClient re-raises unhandled server exceptions, which is what we
        # want to assert against: the route propagates the failure after the
        # storage cleanup runs.
        with pytest.raises(RuntimeError, match="simulated commit failure"):
            _upload(client, business_id, transaction_id, b"will-fail")
    finally:
        monkeypatch.setattr(SASession, "commit", real_commit)

    after = {p.name for p in storage_root.glob("*.jpg")}
    assert after == baseline, "orphan file left on disk after commit failure"


def test_storage_key_rejects_path_traversal_attempts():
    storage = ProofStorage()
    with pytest.raises(HTTPException) as exc:
        storage.local_path("../../etc/passwd")
    assert exc.value.status_code == 404
    with pytest.raises(HTTPException):
        storage.local_path("..\\windows\\system32")
    with pytest.raises(HTTPException):
        storage.local_path("biz/../../escape.jpg")


def test_upload_rejects_svg_image_even_with_image_prefix(client_for):
    # SVG is XML; modern browsers execute inline <script> when an SVG is
    # rendered via <img src> or a same-origin fetch + DOM insert. Allowing it
    # into the proof store creates a latent stored-XSS sink for the future
    # web dashboard. The allowlist must refuse it explicitly.
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)

    svg = b"<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>"
    resp = _upload(
        client, business_id, transaction_id, svg,
        mime_type="image/svg+xml", prepend_magic=False,
    )
    assert resp.status_code == 415


def test_upload_rejects_jpeg_mime_with_non_jpeg_bytes(client_for):
    # Client claims image/jpeg but sends a PNG or arbitrary bytes. Without
    # magic-byte sniffing the server would happily store an .exe under a
    # legitimate-looking proof URL.
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)

    fake_jpeg = PNG_MAGIC + b"rest-of-png-payload"
    resp = _upload(
        client, business_id, transaction_id, fake_jpeg,
        mime_type="image/jpeg", prepend_magic=False,
    )
    assert resp.status_code == 415
    assert "JPEG" in resp.json()["detail"]


def test_upload_accepts_png_with_valid_magic_bytes(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)

    png = PNG_MAGIC + b"valid-png-tail"
    resp = _upload(
        client, business_id, transaction_id, png,
        mime_type="image/png", prepend_magic=False,
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["mime_type"] == "image/png"


def test_upload_rejects_out_of_range_pixel_dimensions(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_id = _create_transaction(client, business_id)

    # width=10_000_000_000 would overflow the Integer column on commit. The
    # 422 guard catches it before SQLAlchemy ever sees the value.
    resp = client.post(
        f"/businesses/{business_id}/screenshots",
        data={
            "transaction_id": str(transaction_id),
            "original_file_size": "100",
            "width": "10000000000",
            "height": "1080",
        },
        files={"file": ("proof.jpg", JPEG_MAGIC + b"payload", "image/jpeg")},
    )
    assert resp.status_code == 422
    assert "between" in resp.json()["detail"]


def test_transaction_create_rejects_oversized_note():
    """Authenticated users mustn't be able to wedge megabytes of text into
    a transaction row; that bloats list responses and burns server RAM."""
    user_id = uuid.uuid4()
    profile = Profile(id=user_id, email="alice@example.com")
    s = TestingSessionLocal()
    s.add(profile)
    s.commit()
    s.close()

    def _override_user():
        s2 = TestingSessionLocal()
        try:
            yield s2.get(Profile, user_id)
        finally:
            s2.close()

    app.dependency_overrides[get_db] = _override_db
    app.dependency_overrides[get_current_user] = _override_user
    client = TestClient(app)
    try:
        business_id = _create_business(client)
        # 5,000-char note > the 2_000 cap.
        resp = client.post(
            f"/businesses/{business_id}/transactions",
            json={
                "id": str(uuid.uuid4()),
                "amount_minor": 12345,
                "currency": "NGN",
                "direction": "money_in",
                "transaction_date": "2026-05-24",
                "note": "x" * 5_000,
            },
        )
        assert resp.status_code == 422
    finally:
        app.dependency_overrides.clear()


def test_transaction_create_rejects_amount_above_cap():
    user_id = uuid.uuid4()
    s = TestingSessionLocal()
    s.add(Profile(id=user_id, email="alice@example.com"))
    s.commit()
    s.close()

    def _override_user():
        s2 = TestingSessionLocal()
        try:
            yield s2.get(Profile, user_id)
        finally:
            s2.close()

    app.dependency_overrides[get_db] = _override_db
    app.dependency_overrides[get_current_user] = _override_user
    client = TestClient(app)
    try:
        business_id = _create_business(client)
        resp = client.post(
            f"/businesses/{business_id}/transactions",
            json={
                "id": str(uuid.uuid4()),
                # 10x the ₦999,999,999,999.99 cap.
                "amount_minor": 99_999_999_999_99 * 10,
                "currency": "NGN",
                "direction": "money_in",
                "transaction_date": "2026-05-24",
            },
        )
        assert resp.status_code == 422
    finally:
        app.dependency_overrides.clear()


def test_list_screenshots_filters_by_transaction_id(client_for):
    client = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(client)
    transaction_a = _create_transaction(client, business_id)
    transaction_b = _create_transaction(client, business_id)
    _upload(client, business_id, transaction_a, b"a1")
    _upload(client, business_id, transaction_a, b"a2")
    _upload(client, business_id, transaction_b, b"b1")

    filtered = client.get(
        f"/businesses/{business_id}/screenshots?transaction_id={transaction_a}"
    ).json()
    assert len(filtered) == 2
    assert all(row["transaction_id"] == str(transaction_a) for row in filtered)


