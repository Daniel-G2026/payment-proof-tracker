from fastapi import FastAPI

from app.api.routes import businesses, health, profiles
from app.core.config import settings

app = FastAPI(title="Payment Proof Tracker API", version="0.2.0")

app.include_router(health.router)
app.include_router(profiles.router)
app.include_router(businesses.router)


@app.get("/")
async def root() -> dict[str, str]:
    return {"service": "proofledger-backend", "environment": settings.environment}
