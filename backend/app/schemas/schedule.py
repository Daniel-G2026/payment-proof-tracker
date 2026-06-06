from __future__ import annotations

import uuid
from datetime import date, datetime

from pydantic import BaseModel, ConfigDict, Field

# A house price in minor units fits comfortably under this bound (same rationale
# as the unit total-price cap); rejects garbage without limiting real schedules.
MAX_INSTALLMENT_AMOUNT_MINOR = 10**15
MAX_INSTALLMENTS = 600  # e.g. monthly for 50 years — far beyond any real plan.


class InstallmentInput(BaseModel):
    id: uuid.UUID
    sequence: int = Field(ge=0)
    amount_minor: int = Field(gt=0, le=MAX_INSTALLMENT_AMOUNT_MINOR)
    due_date: date


class ScheduleVersionCreate(BaseModel):
    id: uuid.UUID
    source: str = Field(default="manual", pattern="^(manual|offer_letter)$")
    offer_letter_screenshot_id: uuid.UUID | None = None
    installments: list[InstallmentInput] = Field(min_length=1, max_length=MAX_INSTALLMENTS)


class InstallmentOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    business_id: uuid.UUID
    schedule_version_id: uuid.UUID
    sequence: int
    amount_minor: int
    due_date: date
    created_at: datetime
    updated_at: datetime
    deleted_at: datetime | None


class ScheduleVersionOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    business_id: uuid.UUID
    unit_id: uuid.UUID
    version_no: int
    source: str
    is_active: bool
    offer_letter_screenshot_id: uuid.UUID | None
    created_at: datetime
    updated_at: datetime
    deleted_at: datetime | None
    installments: list[InstallmentOut] = []
