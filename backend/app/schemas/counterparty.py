from __future__ import annotations

import uuid
from datetime import date, datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

COUNTERPARTY_TYPE_PATTERN = (
    "^(customer|supplier|subcontractor|employee|driver|landlord|agent|other|unknown)$"
)


class CounterpartyCreate(BaseModel):
    id: uuid.UUID
    name: str = Field(min_length=1, max_length=300)
    type: str = Field(pattern=COUNTERPARTY_TYPE_PATTERN)
    phone: str | None = None
    notes: str | None = None


class CounterpartyUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=300)
    type: str | None = Field(default=None, pattern=COUNTERPARTY_TYPE_PATTERN)
    phone: str | None = None
    notes: str | None = None

    @field_validator("name", "type")
    @classmethod
    def _reject_explicit_null(cls, value, info):
        if value is None:
            raise ValueError(f"{info.field_name} cannot be null")
        return value


class CounterpartyOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    business_id: uuid.UUID
    name: str
    normalized_name: str
    type: str
    phone: str | None
    notes: str | None
    created_at: datetime
    updated_at: datetime
    deleted_at: datetime | None


class CounterpartySummaryOut(BaseModel):
    total_in: int
    total_out: int
    last_transaction_date: date | None = None
    transaction_count: int
