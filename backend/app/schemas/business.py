from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.schemas.currency import validate_currency

MAX_BUSINESS_NAME_LEN = 200
MAX_COUNTRY_LEN = 100
MAX_BUSINESS_TYPE_LEN = 100


class BusinessCreate(BaseModel):
    """Client provides the UUID for offline-first / idempotent creation."""

    id: uuid.UUID
    name: str = Field(min_length=1, max_length=MAX_BUSINESS_NAME_LEN)
    country: str = Field(default="Nigeria", max_length=MAX_COUNTRY_LEN)
    currency: str = "NGN"
    business_type: str | None = Field(default=None, max_length=MAX_BUSINESS_TYPE_LEN)

    @field_validator("currency")
    @classmethod
    def _check_currency(cls, value):
        return validate_currency(value)


class BusinessUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=MAX_BUSINESS_NAME_LEN)
    country: str | None = Field(default=None, max_length=MAX_COUNTRY_LEN)
    currency: str | None = None
    business_type: str | None = Field(default=None, max_length=MAX_BUSINESS_TYPE_LEN)

    @field_validator("currency")
    @classmethod
    def _check_currency(cls, value):
        return validate_currency(value)


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
