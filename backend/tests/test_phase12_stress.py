"""Phase 12 Cut 1 — stress / burst tests.

Pushes the hardening + observability surfaces under repetition to confirm they
stay deterministic and don't degrade: a 200-request upload burst against a
small cap, a flood of adversarial wildcard search terms, and repeated
observability init.
"""

from __future__ import annotations

import uuid
from typing import Any

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.api.deps import get_current_user, get_db
from app.core import observability
from app.core.config import settings
from app.db.base import Base
from app.db.models import Profile
from app.main import app

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
    settings.extraction_enabled = False
    app.state.arq_pool = None
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)
    app.state.arq_pool = None
    settings.upload_rate_per_minute = 20
    settings.upload_rate_per_hour = 200


def _override_db():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()


@pytest.fixture
def client():
    user_id = uuid.uuid4()
    db = TestingSessionLocal()
    db.add(Profile(id=user_id, email="stress@example.com"))
    db.commit()
    db.close()

    def _override_user():
        db = TestingSessionLocal()
        try:
            yield db.get(Profile, user_id)
        finally:
            db.close()

    app.dependency_overrides[get_db] = _override_db
    app.dependency_overrides[get_current_user] = _override_user
    yield TestClient(app)
    app.dependency_overrides.clear()


class FakePipeline:
    def __init__(self, store: dict[str, int]):
        self._store = store
        self._ops: list[tuple[str, str]] = []

    def incr(self, key: str):
        self._ops.append(("incr", key))
        return self

    def expire(self, key: str, seconds: int):
        self._ops.append(("expire", key))
        return self

    async def execute(self) -> list[Any]:
        out: list[Any] = []
        for op, key in self._ops:
            if op == "incr":
                self._store[key] = self._store.get(key, 0) + 1
                out.append(self._store[key])
            else:
                out.append(True)
        return out


class FakeArqPool:
    def __init__(self):
        self.counters: dict[str, int] = {}

    def pipeline(self) -> FakePipeline:
        return FakePipeline(self.counters)


def _business(client: TestClient) -> uuid.UUID:
    bid = uuid.uuid4()
    assert client.post("/businesses", json={"id": str(bid), "name": "Stress Co"}).status_code == 200
    return bid


def _upload(client, bid):
    body = JPEG_MAGIC + b"x"
    return client.post(
        f"/businesses/{bid}/screenshots",
        data={"original_file_size": str(len(body)), "width": "10", "height": "10"},
        files={"file": ("p.jpg", body, "image/jpeg")},
    )


def test_upload_burst_caps_deterministically(client):
    bid = _business(client)
    settings.upload_rate_per_minute = 5
    settings.upload_rate_per_hour = 1000
    app.state.arq_pool = FakeArqPool()

    codes = [_upload(client, bid).status_code for _ in range(200)]
    # Exactly the first 5 pass; the remaining 195 are throttled. No 500s.
    assert codes.count(200) == 5
    assert codes.count(429) == 195
    assert set(codes) == {200, 429}


def test_adversarial_search_terms_never_error(client):
    bid = _business(client)
    for note, cp in [("50% off", "Ada"), ("a_b_c", "Bola"), ("plain", "Cibu")]:
        client.post(
            f"/businesses/{bid}/transactions",
            json={
                "id": str(uuid.uuid4()),
                "amount_minor": 1000,
                "currency": "NGN",
                "direction": "money_in",
                "transaction_date": "2026-05-01",
                "note": note,
                "counterparty_name": cp,
            },
        )

    adversarial = ["%", "_", "\\", "%%", "__", "50%", "a_b_c", "x" * 200, "100%_off\\"]
    for term in adversarial:
        resp = client.get(f"/businesses/{bid}/transactions", params={"search": term})
        assert resp.status_code == 200, (term, resp.text)

    # A literal "%" must not match every row via wildcarding.
    only_percent = client.get(
        f"/businesses/{bid}/transactions", params={"search": "%"}
    ).json()
    assert all("%" in (r["note"] or "") for r in only_percent)
    assert {r["note"] for r in only_percent} == {"50% off"}


def test_repeated_observability_init_is_stable():
    original = settings.sentry_dsn
    settings.sentry_dsn = ""
    try:
        for _ in range(50):
            observability.configure_logging()
            assert observability.init_sentry() is False
    finally:
        settings.sentry_dsn = original
