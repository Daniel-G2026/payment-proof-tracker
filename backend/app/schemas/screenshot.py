from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict


class ScreenshotOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    business_id: uuid.UUID
    # Phase 8.1: nullable to support orphan uploads. Set atomically by the
    # transaction-create endpoint when the user saves the form.
    transaction_id: uuid.UUID | None
    uploaded_by: uuid.UUID
    mime_type: str
    uploaded_file_size: int
    original_file_size: int
    width: int | None
    height: int | None
    storage_key: str
    storage_status: str
    created_at: datetime
    updated_at: datetime
    deleted_at: datetime | None
    # Status of the server-side extraction job, when one has been enqueued for
    # this screenshot. None if the feature was disabled or hasn't run yet.
    draft_status: str | None = None


class SignedScreenshotUrl(BaseModel):
    url: str
    expires_at: datetime
