from __future__ import annotations

import uuid
from datetime import date, datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.schedule import MAX_INSTALLMENT_AMOUNT_MINOR, MAX_INSTALLMENTS


class ScheduleDraftOut(BaseModel):
    """Server response shape for a single offer-letter schedule draft.

    Cleaned per-field values plus per-field confidence (0-1) on a separate
    `confidence` dict, so the client can flag low-confidence fields. The
    `installments` array is `null` when it couldn't be read with confidence —
    the review screen then opens an empty (manual) editor.
    """

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    screenshot_id: uuid.UUID
    business_id: uuid.UUID
    status: str

    buyer_name: str | None
    total_minor: int | None
    currency_code: str | None
    installments: list[dict[str, Any]] | None

    confidence: dict[str, Any] | None
    error_code: str | None
    error_message: str | None
    model_version: str | None
    attempts: int

    created_at: datetime
    updated_at: datetime
    confirmed_at: datetime | None


class ScheduleDuplicateInstallmentIn(BaseModel):
    """One installment line of the schedule being checked (amount + due date —
    sequence is irrelevant to a duplicate, the plan is matched as a set)."""

    amount_minor: int = Field(gt=0, le=MAX_INSTALLMENT_AMOUNT_MINOR)
    due_date: date


class ScheduleDuplicateCandidateIn(BaseModel):
    """Advisory pre-confirm check: has this scanned offer letter already been
    entered as a schedule elsewhere in the business? Matched on the extracted
    fields (buyer + the installment plan) and on the proof image's bytes — never
    by re-reading the document. Mirrors the Phase 9 transaction duplicate check.

    `exclude_unit_id` is the unit currently being confirmed; the buyer+schedule
    signal skips it so a legitimate renegotiation/re-entry on the same unit is
    not flagged (an exact-image re-scan still surfaces via `same_proof`).
    """

    buyer_name: str | None = Field(default=None, max_length=255)
    installments: list[ScheduleDuplicateInstallmentIn] = Field(
        default_factory=list, max_length=MAX_INSTALLMENTS
    )
    screenshot_id: uuid.UUID | None = None
    exclude_unit_id: uuid.UUID | None = None


class ScheduleDuplicateCandidateOut(BaseModel):
    """A prior schedule that looks like the one being confirmed. `reasons`/`score`
    mirror the transaction duplicate shape so the client renders them the same."""

    unit_id: uuid.UUID
    project_id: uuid.UUID
    unit_label: str
    buyer_name: str | None
    schedule_version_id: uuid.UUID
    version_no: int
    installment_count: int
    total_minor: int
    reasons: list[str]
    score: int
