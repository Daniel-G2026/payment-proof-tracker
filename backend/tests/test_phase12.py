"""Phase 12 Cut 1 — security/validation hardening tests.

Covers: upload rate-limiting (429 over-limit + fail-open without Redis),
currency ISO-4217 allowlist (422 + normalization), search LIKE-escaping (a
literal ``%`` term doesn't wildcard), and CORS wiring.

DB plumbing mirrors test_phase5_screenshots.py; the arq pool is a hand-built
stub with the same pipeline interface used in test_phase8_extraction.py.
"""

from __future__ import annotations

import uuid
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.api.deps import get_current_user, get_db
from app.core.config import settings
from app.db.base import Base
from app.db.models import Profile
from app.main import app, configure_cors, parse_cors_origins

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
TestingSessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)

JPEG_MAGIC = b"\xff\xd8\xff\xe0"


@pytest.fixture(autouse=True)
def setup_db(tmp_path):
    settings.proof_storage_backend = "local"
    settings.proof_storage_local_dir = str(tmp_path / "proofs")
    settings.environment = "development"
    # Known defaults so one test's overrides don't bleed into the next.
    settings.upload_rate_per_minute = 20
    settings.upload_rate_per_hour = 200
    settings.extraction_enabled = False  # isolate the upload path from enqueue
    app.state.arq_pool = None
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)
    app.state.arq_pool = None
    settings.upload_rate_per_minute = 20
    settings.upload_rate_per_hour = 200
    settings.extraction_enabled = True


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
    def _build(user_id: uuid.UUID, email: str) -> TestClient:
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


# --- arq pool stub (same shape as test_phase8_extraction) ----------------


class FakePipeline:
    def __init__(self, store: dict[str, int]):
        self._store = store
        self._ops: list[tuple[str, str, int | None]] = []

    def incr(self, key: str) -> FakePipeline:
        self._ops.append(("incr", key, None))
        return self

    def expire(self, key: str, seconds: int) -> FakePipeline:
        self._ops.append(("expire", key, seconds))
        return self

    async def execute(self) -> list[Any]:
        results: list[Any] = []
        for op, key, _ in self._ops:
            if op == "incr":
                self._store[key] = self._store.get(key, 0) + 1
                results.append(self._store[key])
            else:
                results.append(True)
        return results


class FakeArqPool:
    def __init__(self):
        self.counters: dict[str, int] = {}

    def pipeline(self) -> FakePipeline:
        return FakePipeline(self.counters)

    async def enqueue_job(self, name: str, *args: Any) -> None:  # pragma: no cover
        pass


# --- helpers -------------------------------------------------------------


def _create_business(client: TestClient, currency: str = "NGN"):
    business_id = uuid.uuid4()
    return business_id, client.post(
        "/businesses",
        json={"id": str(business_id), "name": "Ledger Co", "currency": currency},
    )


def _create_transaction(client, business_id, *, note=None, counterparty=None, currency="NGN"):
    payload = {
        "id": str(uuid.uuid4()),
        "amount_minor": 125000,
        "currency": currency,
        "direction": "money_in",
        "transaction_date": "2026-05-24",
    }
    if note is not None:
        payload["note"] = note
    if counterparty is not None:
        payload["counterparty_name"] = counterparty
    return client.post(f"/businesses/{business_id}/transactions", json=payload)


def _upload_orphan(client, business_id, content=b"proof"):
    body = JPEG_MAGIC + content
    return client.post(
        f"/businesses/{business_id}/screenshots",
        data={"original_file_size": str(len(body)), "width": "100", "height": "100"},
        files={"file": ("proof.jpg", body, "image/jpeg")},
    )


# --- currency allowlist --------------------------------------------------


def test_business_create_rejects_unknown_currency(client_for):
    client = client_for(uuid.uuid4(), "a@example.com")
    _, resp = _create_business(client, currency="XYZ")
    assert resp.status_code == 422, resp.text


def test_business_create_normalizes_currency_case(client_for):
    client = client_for(uuid.uuid4(), "a@example.com")
    _, resp = _create_business(client, currency="ngn")
    assert resp.status_code == 200, resp.text
    assert resp.json()["currency"] == "NGN"


def test_transaction_create_rejects_unknown_currency(client_for):
    client = client_for(uuid.uuid4(), "a@example.com")
    business_id, _ = _create_business(client)
    resp = _create_transaction(client, business_id, currency="ZZZ")
    assert resp.status_code == 422, resp.text


# --- search LIKE escaping ------------------------------------------------


def test_search_escapes_percent_wildcard(client_for):
    client = client_for(uuid.uuid4(), "a@example.com")
    business_id, _ = _create_business(client)
    # One note literally contains "50%"; the other starts with "50" but is not
    # a literal "50%". An unescaped LIKE "%50%%" would match BOTH.
    assert _create_transaction(
        client, business_id, note="50% discount", counterparty="Ada"
    ).status_code == 200
    assert _create_transaction(
        client, business_id, note="5000 units", counterparty="Bola"
    ).status_code == 200

    resp = client.get(f"/businesses/{business_id}/transactions", params={"search": "50%"})
    assert resp.status_code == 200, resp.text
    notes = sorted(row["note"] for row in resp.json())
    assert notes == ["50% discount"]


# --- upload rate limit ---------------------------------------------------


def test_upload_rate_limit_returns_429_over_limit(client_for):
    client = client_for(uuid.uuid4(), "a@example.com")
    business_id, _ = _create_business(client)
    settings.upload_rate_per_minute = 1
    settings.upload_rate_per_hour = 100
    app.state.arq_pool = FakeArqPool()

    first = _upload_orphan(client, business_id)
    second = _upload_orphan(client, business_id)
    assert first.status_code == 200, first.text
    assert second.status_code == 429, second.text


def test_upload_rate_limit_fails_open_without_pool(client_for):
    client = client_for(uuid.uuid4(), "a@example.com")
    business_id, _ = _create_business(client)
    settings.upload_rate_per_minute = 1
    settings.upload_rate_per_hour = 100
    app.state.arq_pool = None  # Redis absent → fail open, never block uploads

    first = _upload_orphan(client, business_id)
    second = _upload_orphan(client, business_id)
    assert first.status_code == 200, first.text
    assert second.status_code == 200, second.text


# --- CORS ----------------------------------------------------------------


def test_parse_cors_origins_drops_blanks():
    assert parse_cors_origins("") == []
    assert parse_cors_origins(" https://a.com , ,https://b.com ") == [
        "https://a.com",
        "https://b.com",
    ]


def test_configure_cors_sets_allow_origin_header_for_allowed_origin():
    sub = FastAPI()

    @sub.get("/ping")
    def ping() -> dict[str, str]:
        return {"ok": "1"}

    configure_cors(sub, "https://dash.example.com")
    client = TestClient(sub)
    resp = client.get("/ping", headers={"Origin": "https://dash.example.com"})
    assert resp.status_code == 200
    assert resp.headers["access-control-allow-origin"] == "https://dash.example.com"


def test_configure_cors_noop_when_empty():
    sub = FastAPI()

    @sub.get("/ping")
    def ping() -> dict[str, str]:
        return {"ok": "1"}

    configure_cors(sub, "")
    client = TestClient(sub)
    resp = client.get("/ping", headers={"Origin": "https://dash.example.com"})
    assert "access-control-allow-origin" not in resp.headers
