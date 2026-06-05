from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.routes import (
    audit,
    businesses,
    categories,
    counterparties,
    health,
    profiles,
    reports,
    screenshots,
    transaction_drafts,
    transactions,
)
from app.core.config import settings
from app.core.observability import configure_logging, init_sentry

# Configure logging + Sentry as early as possible (before the app handles any
# request). Both are no-ops without configuration.
configure_logging()
init_sentry()


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Hold the arq Redis pool for the API process so the upload route can
    enqueue extraction jobs without paying the per-request connect cost.

    Pool creation is best-effort: if Redis is unreachable at startup (common
    in dev when the user hasn't booted the docker-compose stack) we log and
    fall through. Uploads still work; they just skip extraction enqueueing.
    """
    # Create the Redis pool when extraction needs it OR when the upload
    # rate-limiter is configured (Phase 12) — both reuse the same pool.
    pool = None
    needs_pool = settings.extraction_enabled or (
        settings.upload_rate_per_minute > 0 or settings.upload_rate_per_hour > 0
    )
    if needs_pool:
        try:
            from arq import create_pool

            from app.services.extraction import build_arq_redis_settings

            pool = await create_pool(build_arq_redis_settings())
        except Exception as exc:  # noqa: BLE001 — defensive: never block API boot on Redis
            import logging

            logging.getLogger(__name__).warning(
                "arq pool unavailable at startup; extraction will be skipped: %s", exc
            )
            pool = None
    app.state.arq_pool = pool
    try:
        yield
    finally:
        if pool is not None:
            await pool.aclose()


app = FastAPI(
    title="Payment Proof Tracker API",
    version="0.3.0",
    lifespan=lifespan,
)

def parse_cors_origins(raw: str) -> list[str]:
    """Split the comma-separated origins config, dropping blanks."""
    return [o.strip() for o in raw.split(",") if o.strip()]


def configure_cors(target: FastAPI, raw_origins: str) -> None:
    """Phase 12 — CORS. Default-empty origins means no cross-origin access (the
    mobile app sends no Origin header, so it's unaffected). The future web
    dashboard's origin is added via the cors_allowed_origins env. Never "*"."""
    origins = parse_cors_origins(raw_origins)
    if origins:
        target.add_middleware(
            CORSMiddleware,
            allow_origins=origins,
            allow_credentials=True,
            allow_methods=["*"],
            allow_headers=["*"],
        )


configure_cors(app, settings.cors_allowed_origins)

app.include_router(health.router)
app.include_router(profiles.router)
app.include_router(businesses.router)
app.include_router(categories.router)
app.include_router(counterparties.router)
app.include_router(transactions.router)
app.include_router(audit.router)
app.include_router(reports.router)
app.include_router(screenshots.router)
app.include_router(transaction_drafts.router)


@app.get("/")
async def root() -> dict[str, str]:
    return {"service": "proofledger-backend", "environment": settings.environment}
