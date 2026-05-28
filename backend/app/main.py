from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import FastAPI

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


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Hold the arq Redis pool for the API process so the upload route can
    enqueue extraction jobs without paying the per-request connect cost.

    Pool creation is best-effort: if Redis is unreachable at startup (common
    in dev when the user hasn't booted the docker-compose stack) we log and
    fall through. Uploads still work; they just skip extraction enqueueing.
    """
    pool = None
    if settings.extraction_enabled:
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
