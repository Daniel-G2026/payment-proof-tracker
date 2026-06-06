from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict


class ScheduleDraftOut(BaseModel):
    """Server response shape for a single offer-letter schedule draft.

    Cleaned per-field values plus per-field confidence (0-1) on a separate
    `confidence` dict, so the client can flag low-confidence fields. The
    `installments` array is `null` when it couldn't be read with confidence —
    the review screen then opens an empty (manual) editor.
    """

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    screenshot_id: uuid.UUID
    business_id: uuid.UUID
    status: str

    buyer_name: str | None
    total_minor: int | None
    currency_code: str | None
    installments: list[dict[str, Any]] | None

    confidence: dict[str, Any] | None
    error_code: str | None
    error_message: str | None
    model_version: str | None
    attempts: int

    created_at: datetime
    updated_at: datetime
    confirmed_at: datetime | None
