from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

MAX_PROFILE_NAME_LEN = 200
MAX_PROFILE_PHONE_LEN = 40


class ProfileOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    full_name: str | None
    phone: str | None
    email: str | None
    created_at: datetime
    updated_at: datetime


class ProfileUpdate(BaseModel):
    full_name: str | None = Field(default=None, max_length=MAX_PROFILE_NAME_LEN)
    phone: str | None = Field(default=None, max_length=MAX_PROFILE_PHONE_LEN)

    @field_validator("phone")
    @classmethod
    def _blank_phone_clears(cls, value: str | None) -> str | None:
        # "" is the wire form of "clear the phone": the Android client can't
        # send an explicit null (Moshi omits null fields, and exclude_unset
        # would then read the missing key as "keep"). Normalizing here keeps
        # the column null instead of storing empty strings.
        if value is not None and not value.strip():
            return None
        return value
