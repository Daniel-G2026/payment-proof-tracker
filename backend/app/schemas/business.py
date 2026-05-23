from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class BusinessCreate(BaseModel):
    """Client provides the UUID for offline-first / idempotent creation."""

    id: uuid.UUID
    name: str = Field(min_length=1, max_length=200)
    country: str = "Nigeria"
    currency: str = "NGN"
    business_type: str | None = None


class BusinessUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=200)
    country: str | None = None
    currency: str | None = None
    business_type: str | None = None


class BusinessOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    owner_id: uuid.UUID
    name: str
    country: str
    currency: str
    business_type: str | None
    plan: str
    created_at: datetime
    updated_at: datetime
