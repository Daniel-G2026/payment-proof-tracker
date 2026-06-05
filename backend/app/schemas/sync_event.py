from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class SyncEventIn(BaseModel):
    """One device-reported sync attempt. `id` is the device-generated event id
    (drives idempotent ingest)."""

    id: uuid.UUID
    entity_type: str = Field(min_length=1, max_length=64)
    entity_id: str | None = Field(default=None, max_length=64)
    action: str = Field(min_length=1, max_length=64)
    status: str = Field(min_length=1, max_length=32)
    error_message: str | None = Field(default=None, max_length=500)
    occurred_at: datetime


class SyncEventOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    business_id: uuid.UUID
    actor_id: uuid.UUID
    entity_type: str
    entity_id: str | None
    action: str
    status: str
    error_message: str | None
    occurred_at: datetime
    received_at: datetime


class SyncEventIngestResult(BaseModel):
    accepted: int
    duplicates: int
