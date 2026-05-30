from fastapi import APIRouter, Request
from sqlalchemy import text
from starlette.responses import JSONResponse

from app.db.session import SessionLocal

router = APIRouter(tags=["health"])


@router.get("/health")
async def health() -> dict[str, str]:
    """Liveness probe: the process is up and serving. Cheap and dependency-free
    so an orchestrator's liveness check never restarts us over a transient DB
    or Redis blip — that's what readiness is for."""
    return {"status": "ok"}


@router.get("/health/ready")
async def readiness(request: Request) -> JSONResponse:
    """Readiness probe: verify the dependencies a request actually needs.

    Returns 503 if Postgres is unreachable so a load balancer stops routing
    traffic to a pod that can't serve. Redis is reported but not fatal — the
    app degrades gracefully without it (extraction enqueue and rate limiting
    fail open), so a Redis outage shouldn't pull the API out of rotation.
    """
    checks: dict[str, str] = {}
    healthy = True

    try:
        with SessionLocal() as db:
            db.execute(text("SELECT 1"))
        checks["database"] = "ok"
    except Exception:
        checks["database"] = "error"
        healthy = False

    redis = getattr(request.app.state, "rate_limit_redis", None) or getattr(
        request.app.state, "arq_pool", None
    )
    if redis is None:
        checks["redis"] = "disabled"
    else:
        try:
            await redis.ping()
            checks["redis"] = "ok"
        except Exception:
            checks["redis"] = "error"

    return JSONResponse(
        status_code=200 if healthy else 503,
        content={"status": "ok" if healthy else "degraded", "checks": checks},
    )
