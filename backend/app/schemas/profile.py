from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

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
