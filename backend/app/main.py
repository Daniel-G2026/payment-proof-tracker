from fastapi import FastAPI

from app.api.routes import health
from app.core.config import settings

app = FastAPI(title="Payment Proof Tracker API", version="0.1.0")

app.include_router(health.router)


@app.get("/")
async def root() -> dict[str, str]:
    return {"service": "proofledger-backend", "environment": settings.environment}
