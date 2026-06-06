from __future__ import annotations

import uuid
from datetime import date, datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.schemas.currency import validate_currency

# Field-length caps: Postgres `text` will happily store megabytes per row, but
# nothing in this product needs that. Caps bound RAM use on list endpoints
# and block bad-apple users sending 50 MB notes. Sized to be generous for
# real-world Nigerian payment data (the longest counterparty name we've seen
# is ~80 chars, the longest reference ~30, etc.).
MAX_COUNTERPARTY_NAME_LEN = 200
MAX_CATEGORY_LEN = 120
MAX_BANK_NAME_LEN = 120
MAX_REFERENCE_LEN = 120
MAX_NOTE_LEN = 2_000

# 9.2 * 10^18 minor units = Long.MAX_VALUE; the cap keeps us well below that
# AND below the largest sane business amount (₦999B). Postgres BIGINT matches.
MAX_AMOUNT_MINOR = 99_999_999_999_99  # ₦999,999,999,999.99


class TransactionCreate(BaseModel):
    id: uuid.UUID
    amount_minor: int = Field(gt=0, le=MAX_AMOUNT_MINOR)
    currency: str = "NGN"
    direction: str = Field(pattern="^(money_in|money_out)$")
    transaction_date: date
    counterparty_id: uuid.UUID | None = None
    counterparty_name: str | None = Field(default=None, max_length=MAX_COUNTERPARTY_NAME_LEN)
    category: str | None = Field(default=None, max_length=MAX_CATEGORY_LEN)
    # Phase 13 Cut 3: money-in may link to a unit + optionally earmark a payment
    # to a specific installment. null = no link / pure waterfall.
    unit_id: uuid.UUID | None = None
    earmarked_installment_id: uuid.UUID | None = None
    note: str | None = Field(default=None, max_length=MAX_NOTE_LEN)
    bank_name: str | None = Field(default=None, max_length=MAX_BANK_NAME_LEN)
    reference_number: str | None = Field(default=None, max_length=MAX_REFERENCE_LEN)
    # Phase 8.1: link an orphan screenshot uploaded eagerly during the form
    # session. Server atomically sets screenshots.transaction_id = this txn
    # in the same commit. Validated for ownership / not-already-linked in
    # the route.
    screenshot_id: uuid.UUID | None = None

    @field_validator("currency")
    @classmethod
    def _check_currency(cls, value):
        return validate_currency(value)


class TransactionUpdate(BaseModel):
    """PATCH payload. Omitted fields stay unchanged.

    Required-on-the-row fields (`amount_minor`, `direction`) accept omission
    but reject explicit `null` — sending `{"direction": null}` would otherwise
    overwrite a NOT NULL column and 500 inside SQLAlchemy. Optional text fields
    still accept `null` so the user can clear them.
    """

    amount_minor: int | None = Field(default=None, gt=0, le=MAX_AMOUNT_MINOR)
    currency: str | None = None
    direction: str | None = Field(default=None, pattern="^(money_in|money_out)$")
    transaction_date: date | None = None
    counterparty_id: uuid.UUID | None = None
    counterparty_name: str | None = Field(default=None, max_length=MAX_COUNTERPARTY_NAME_LEN)
    category: str | None = Field(default=None, max_length=MAX_CATEGORY_LEN)
    # Phase 13 Cut 3: clearable (null is allowed to unlink / drop an earmark).
    unit_id: uuid.UUID | None = None
    earmarked_installment_id: uuid.UUID | None = None
    note: str | None = Field(default=None, max_length=MAX_NOTE_LEN)
    bank_name: str | None = Field(default=None, max_length=MAX_BANK_NAME_LEN)
    reference_number: str | None = Field(default=None, max_length=MAX_REFERENCE_LEN)
    # Phase 8.1: same as TransactionCreate.screenshot_id — link an orphan
    # screenshot on PATCH (used when editing a transaction and replacing
    # the proof via the form's eager-upload path).
    screenshot_id: uuid.UUID | None = None
    # Optimistic concurrency: the `updated_at` the client saw when it opened
    # the record. When present, the server returns 409 if the row has been
    # modified since. Omitted = no check (preserves existing single-device
    # behavior).
    expected_updated_at: datetime | None = None

    @field_validator("amount_minor", "direction")
    @classmethod
    def _reject_explicit_null(cls, value, info):
        if value is None:
            raise ValueError(f"{info.field_name} cannot be null")
        return value

    @field_validator("currency")
    @classmethod
    def _check_currency(cls, value):
        return validate_currency(value)


class TransactionOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    business_id: uuid.UUID
    amount_minor: int
    currency: str
    direction: str
    transaction_date: date
    counterparty_id: uuid.UUID | None
    counterparty_name: str | None
    category: str | None
    project_id: uuid.UUID | None
    unit_id: uuid.UUID | None
    earmarked_installment_id: uuid.UUID | None
    note: str | None
    bank_name: str | None
    reference_number: str | None
    source: str
    status: str
    created_by: uuid.UUID
    created_at: datetime
    updated_at: datetime
    deleted_at: datetime | None


class DuplicateCandidateIn(BaseModel):
    amount_minor: int = Field(gt=0, le=MAX_AMOUNT_MINOR)
    direction: str = Field(pattern="^(money_in|money_out)$")
    transaction_date: date
    counterparty_id: uuid.UUID | None = None
    counterparty_name: str | None = Field(default=None, max_length=MAX_COUNTERPARTY_NAME_LEN)
    reference_number: str | None = Field(default=None, max_length=MAX_REFERENCE_LEN)
    screenshot_id: uuid.UUID | None = None
    exclude_transaction_id: uuid.UUID | None = None


class DuplicateCandidateOut(BaseModel):
    transaction: TransactionOut
    reasons: list[str]
    score: int
