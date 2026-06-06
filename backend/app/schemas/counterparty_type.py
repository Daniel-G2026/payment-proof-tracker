from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

# Generous-but-bounded text cap. Same rationale as the category schemas: an
# authenticated user should never be able to store megabytes of text.
MAX_COUNTERPARTY_TYPE_NAME_LEN = 300


class CounterpartyTypeCreate(BaseModel):
    id: uuid.UUID
    name: str = Field(min_length=1, max_length=MAX_COUNTERPARTY_TYPE_NAME_LEN)


class CounterpartyTypeUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=MAX_COUNTERPARTY_TYPE_NAME_LEN)
    is_archived: bool | None = None
    expected_updated_at: datetime | None = None

    @field_validator("name")
    @classmethod
    def _reject_explicit_null(cls, value, info):
        if value is None:
            raise ValueError(f"{info.field_name} cannot be null")
        return value


class CounterpartyTypeOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    business_id: uuid.UUID
    name: str
    normalized_name: str
    is_archived: bool
    created_at: datetime
    updated_at: datetime
    deleted_at: datetime | None
