import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.routes import (
    businesses,
    counterparties,
    health,
    profiles,
    screenshots,
    transaction_drafts,
    transactions,
)
from app.core.config import settings
from app.core.middleware import RequestLogMiddleware, SecurityHeadersMiddleware
from app.core.observability import configure_logging, init_sentry

configure_logging()
init_sentry("api")

log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Hold process-lifetime resources for the API: the arq Redis pool used to
    enqueue extraction jobs, and a Redis client used by the HTTP rate limiter
    and readiness probe.

    Both are best-effort: if Redis is unreachable at startup (common in dev
    when the docker-compose stack isn't up) we log and fall through. Uploads
    still work; they just skip extraction enqueueing and rate limiting.
    """
    pool = None
    if settings.extraction_enabled:
        try:
            from arq import create_pool

            from app.services.extraction import build_arq_redis_settings

            pool = await create_pool(build_arq_redis_settings())
        except Exception as exc:  # noqa: BLE001 — defensive: never block API boot on Redis
            log.warning("arq pool unavailable at startup; extraction will be skipped: %s", exc)
            pool = None
    app.state.arq_pool = pool

    rate_limit_redis = None
    if settings.rate_limit_enabled:
        try:
            import redis.asyncio as aioredis

            # Short timeouts so a Redis outage degrades to "fail open" fast
            # rather than hanging request handling.
            rate_limit_redis = aioredis.from_url(
                settings.redis_url,
                socket_connect_timeout=0.5,
                socket_timeout=0.5,
            )
        except Exception as exc:  # noqa: BLE001
            log.warning("rate-limit Redis unavailable at startup; limiting disabled: %s", exc)
            rate_limit_redis = None
    app.state.rate_limit_redis = rate_limit_redis

    try:
        yield
    finally:
        if pool is not None:
            await pool.aclose()
        if rate_limit_redis is not None:
            await rate_limit_redis.aclose()


app = FastAPI(
    title="Payment Proof Tracker API",
    version="0.4.0",
    lifespan=lifespan,
)

# Outermost: log every request and stamp a request id. Then security headers.
app.add_middleware(RequestLogMiddleware)
app.add_middleware(SecurityHeadersMiddleware)

# CORS stays off until a web dashboard ships and an origin is configured.
# Only the configured origin(s) are allowed — never "*" (master-plan §12).
if settings.cors_origins_list:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins_list,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

app.include_router(health.router)
app.include_router(profiles.router)
app.include_router(businesses.router)
app.include_router(counterparties.router)
app.include_router(transactions.router)
app.include_router(screenshots.router)
app.include_router(transaction_drafts.router)


@app.get("/")
async def root() -> dict[str, str]:
    return {"service": "proofledger-backend", "environment": settings.environment}
