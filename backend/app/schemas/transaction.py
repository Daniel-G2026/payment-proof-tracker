from __future__ import annotations

import uuid
from datetime import date, datetime

from pydantic import BaseModel, ConfigDict, Field


class TransactionCreate(BaseModel):
    id: uuid.UUID
    amount_minor: int = Field(gt=0)
    currency: str = "NGN"
    direction: str = Field(pattern="^(money_in|money_out)$")
    transaction_date: date
    counterparty_name: str | None = None
    category: str | None = None
    note: str | None = None
    bank_name: str | None = None
    reference_number: str | None = None


class TransactionUpdate(BaseModel):
    amount_minor: int | None = Field(default=None, gt=0)
    currency: str | None = None
    direction: str | None = Field(default=None, pattern="^(money_in|money_out)$")
    transaction_date: date | None = None
    counterparty_name: str | None = None
    category: str | None = None
    note: str | None = None
    bank_name: str | None = None
    reference_number: str | None = None


class TransactionOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    business_id: uuid.UUID
    amount_minor: int
    currency: str
    direction: str
    transaction_date: date
    counterparty_name: str | None
    category: str | None
    note: str | None
    bank_name: str | None
    reference_number: str | None
    source: str
    status: str
    created_by: uuid.UUID
    created_at: datetime
    updated_at: datetime
    deleted_at: datetime | None
