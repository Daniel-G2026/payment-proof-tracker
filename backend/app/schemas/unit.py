from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.schemas.currency import validate_currency

MAX_UNIT_LABEL_LEN = 300
MAX_UNIT_STATUS_LEN = 64
# Total offer-letter price cap mirrors the transaction amount bound — large
# enough for real house prices in minor units, small enough to reject garbage.
MAX_UNIT_TOTAL_PRICE_MINOR = 10**15


class UnitCreate(BaseModel):
    id: uuid.UUID
    project_id: uuid.UUID
    label: str = Field(min_length=1, max_length=MAX_UNIT_LABEL_LEN)
    buyer_counterparty_id: uuid.UUID | None = None
    total_price_minor: int | None = Field(default=None, ge=0, le=MAX_UNIT_TOTAL_PRICE_MINOR)
    currency: str = "NGN"
    status: str | None = Field(default=None, max_length=MAX_UNIT_STATUS_LEN)

    @field_validator("currency")
    @classmethod
    def _check_currency(cls, value):
        return validate_currency(value)


class UnitUpdate(BaseModel):
    label: str | None = Field(default=None, min_length=1, max_length=MAX_UNIT_LABEL_LEN)
    buyer_counterparty_id: uuid.UUID | None = None
    total_price_minor: int | None = Field(default=None, ge=0, le=MAX_UNIT_TOTAL_PRICE_MINOR)
    currency: str | None = None
    status: str | None = Field(default=None, max_length=MAX_UNIT_STATUS_LEN)
    expected_updated_at: datetime | None = None

    @field_validator("currency")
    @classmethod
    def _check_currency(cls, value):
        return validate_currency(value)

    @field_validator("label", "status")
    @classmethod
    def _reject_explicit_null(cls, value, info):
        if value is None:
            raise ValueError(f"{info.field_name} cannot be null")
        return value


class UnitOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    business_id: uuid.UUID
    project_id: uuid.UUID
    label: str
    buyer_counterparty_id: uuid.UUID | None
    total_price_minor: int | None
    currency: str
    status: str
    created_at: datetime
    updated_at: datetime
    deleted_at: datetime | None
