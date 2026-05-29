from __future__ import annotations

import uuid
from datetime import date, datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

COUNTERPARTY_TYPE_PATTERN = "^[a-z][a-z0-9_]{0,39}$"

# Generous-but-bounded text caps. Same rationale as the transaction schemas:
# we never want an authenticated user to be able to store megabytes of notes.
MAX_COUNTERPARTY_NAME_LEN = 300
MAX_COUNTERPARTY_PHONE_LEN = 40
MAX_COUNTERPARTY_NOTES_LEN = 2_000


class CounterpartyCreate(BaseModel):
    id: uuid.UUID
    name: str = Field(min_length=1, max_length=MAX_COUNTERPARTY_NAME_LEN)
    type: str = Field(pattern=COUNTERPARTY_TYPE_PATTERN)
    phone: str | None = Field(default=None, max_length=MAX_COUNTERPARTY_PHONE_LEN)
    notes: str | None = Field(default=None, max_length=MAX_COUNTERPARTY_NOTES_LEN)


class CounterpartyUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=MAX_COUNTERPARTY_NAME_LEN)
    type: str | None = Field(default=None, pattern=COUNTERPARTY_TYPE_PATTERN)
    phone: str | None = Field(default=None, max_length=MAX_COUNTERPARTY_PHONE_LEN)
    notes: str | None = Field(default=None, max_length=MAX_COUNTERPARTY_NOTES_LEN)
    expected_updated_at: datetime | None = None

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
