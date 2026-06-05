from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

# Generous-but-bounded text caps, same rationale as the category/counterparty
# schemas: never let an authenticated user store megabytes of text.
MAX_PROJECT_NAME_LEN = 300
MAX_PROJECT_NOTE_LEN = 2000


class ProjectCreate(BaseModel):
    id: uuid.UUID
    name: str = Field(min_length=1, max_length=MAX_PROJECT_NAME_LEN)
    note: str | None = Field(default=None, max_length=MAX_PROJECT_NOTE_LEN)


class ProjectUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=MAX_PROJECT_NAME_LEN)
    note: str | None = Field(default=None, max_length=MAX_PROJECT_NOTE_LEN)
    expected_updated_at: datetime | None = None

    @field_validator("name")
    @classmethod
    def _reject_explicit_null(cls, value, info):
        if value is None:
            raise ValueError(f"{info.field_name} cannot be null")
        return value


class ProjectOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    business_id: uuid.UUID
    name: str
    note: str | None
    created_at: datetime
    updated_at: datetime
    deleted_at: datetime | None
