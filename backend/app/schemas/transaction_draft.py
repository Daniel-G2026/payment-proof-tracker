from __future__ import annotations

import uuid
from datetime import date, datetime
from typing import Any

from pydantic import BaseModel, ConfigDict


class TransactionDraftOut(BaseModel):
    """Server response shape for a single draft.

    The cleaned per-field values plus per-field confidence (0-1) on a separate
    `confidence` dict, so the client can highlight low-confidence fields if it
    wants without us interleaving the schema.
    """

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    screenshot_id: uuid.UUID
    business_id: uuid.UUID
    status: str

    amount_minor: int | None
    currency_code: str | None
    transaction_date: date | None
    reference_number: str | None
    bank_name: str | None
    counterparty_name: str | None

    confidence: dict[str, Any] | None
    error_code: str | None
    error_message: str | None
    model_version: str | None
    attempts: int

    created_at: datetime
    updated_at: datetime
    confirmed_at: datetime | None


class TransactionDraftConfirmIn(BaseModel):
    """Telemetry payload — client tells us which saved transaction this draft
    ultimately produced, so we can later measure draft acceptance rate.
    """

    transaction_id: uuid.UUID
