"""Phase 12: observability + hardening.

Covers the cross-cutting pieces added in the reliability pass:

- security response headers on every response
- /health (liveness) and /health/ready (readiness with dependency checks)
- the Redis-backed rate limiter's window math and fail-open behavior

The rate limiter is exercised directly against fakeredis rather than through
the app, so we don't have to stand up the lifespan's Redis client (the suite
runs with RATE_LIMIT_ENABLED=false — see conftest).
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.services.rate_limit import check_rate_limit


@pytest.fixture
def client() -> TestClient:
    with TestClient(app) as c:
        yield c


def test_security_headers_present_on_every_response(client: TestClient) -> None:
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.headers["X-Content-Type-Options"] == "nosniff"
    assert resp.headers["X-Frame-Options"] == "DENY"
    assert resp.headers["Referrer-Policy"] == "no-referrer"
    # A request id is always echoed for tracing.
    assert resp.headers.get("X-Request-ID")


def test_request_id_is_preserved_when_supplied(client: TestClient) -> None:
    resp = client.get("/health", headers={"X-Request-ID": "trace-123"})
    assert resp.headers["X-Request-ID"] == "trace-123"


def test_liveness_is_dependency_free(client: TestClient) -> None:
    resp = client.get("/health")
    assert resp.json() == {"status": "ok"}


def test_readiness_reports_dependency_checks(client: TestClient) -> None:
    resp = client.get("/health/ready")
    # SQLite connectivity check passes; Redis is disabled in the test config.
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["checks"]["database"] == "ok"
    assert body["checks"]["redis"] == "disabled"


@pytest.mark.asyncio
async def test_rate_limiter_allows_under_limit_then_blocks() -> None:
    import fakeredis.aioredis

    redis = fakeredis.aioredis.FakeRedis()
    key = "http:rl:upload:user-a:0"
    # limit=3: first three allowed, fourth rejected.
    results = [
        await check_rate_limit(redis, key=key, limit=3, window=60) for _ in range(4)
    ]
    assert results == [True, True, True, False]


@pytest.mark.asyncio
async def test_rate_limiter_isolates_keys() -> None:
    import fakeredis.aioredis

    redis = fakeredis.aioredis.FakeRedis()
    a = await check_rate_limit(redis, key="http:rl:upload:user-a:0", limit=1, window=60)
    b = await check_rate_limit(redis, key="http:rl:upload:user-b:0", limit=1, window=60)
    # Each user gets their own budget.
    assert a is True
    assert b is True


@pytest.mark.asyncio
async def test_rate_limiter_fails_open_on_redis_error() -> None:
    class BrokenRedis:
        def pipeline(self):  # noqa: ANN201 - test stub
            raise ConnectionError("redis down")

    allowed = await check_rate_limit(
        BrokenRedis(), key="whatever", limit=1, window=60
    )
    assert allowed is True
