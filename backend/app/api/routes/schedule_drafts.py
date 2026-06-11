from __future__ import annotations

import uuid
from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.api.routes.transactions import (
    _normalize_name,
    _require_member,
    _screenshot_hash_for_duplicate_check,
)
from app.db.models import (
    Counterparty,
    Installment,
    ScheduleDraft,
    ScheduleVersion,
    Screenshot,
    Unit,
)
from app.schemas.schedule_draft import (
    ScheduleDraftOut,
    ScheduleDuplicateCandidateIn,
    ScheduleDuplicateCandidateOut,
)

router = APIRouter(tags=["schedule-drafts"])

DbSession = Annotated[Session, Depends(get_db)]

SAME_PROOF_SCORE = 100
SAME_BUYER_AND_SCHEDULE_SCORE = 75
MAX_DUPLICATE_CANDIDATES = 5


def _installment_key(rows: list[tuple[int, date]]) -> tuple[tuple[int, str], ...]:
    """Order-independent canonical form of an installment plan: the sorted set of
    (amount_minor, ISO due date) pairs. Two plans are 'the same schedule' iff
    these match — sequence numbering and row order are ignored."""
    return tuple(sorted((amount, due.isoformat()) for amount, due in rows))


def _load_schedule_draft(db: Session, screenshot_id: uuid.UUID) -> ScheduleDraft | None:
    screenshot = db.get(Screenshot, screenshot_id)
    if screenshot is None or screenshot.deleted_at is not None:
        return None
    # The relationship is uselist=False, so this is at most one row.
    return (
        db.query(ScheduleDraft)
        .filter(ScheduleDraft.screenshot_id == screenshot_id)
        .one_or_none()
    )


@router.get("/screenshots/{screenshot_id}/schedule-draft", response_model=ScheduleDraftOut)
def get_schedule_draft(
    screenshot_id: uuid.UUID,
    current_user: CurrentUser,
    db: DbSession,
) -> ScheduleDraftOut:
    """Poll endpoint for offer-letter extraction. 404 means the worker hasn't
    created the row yet — the client keeps polling for up to ~60s. 200 returns
    the latest state (queued / processing / ready / failed)."""
    screenshot = db.get(Screenshot, screenshot_id)
    if screenshot is None or screenshot.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Screenshot not found.")
    _require_member(db, current_user.id, screenshot.business_id)
    draft = _load_schedule_draft(db, screenshot_id)
    if draft is None:
        raise HTTPException(status_code=404, detail="Draft not ready.")
    return ScheduleDraftOut.model_validate(draft)


@router.post(
    "/businesses/{business_id}/schedule-drafts/duplicate-candidates",
    response_model=list[ScheduleDuplicateCandidateOut],
)
def schedule_duplicate_candidates(
    business_id: uuid.UUID,
    payload: ScheduleDuplicateCandidateIn,
    current_user: CurrentUser,
    db: DbSession,
) -> list[ScheduleDuplicateCandidateOut]:
    """Advisory: prior schedules that look like the offer letter being confirmed.

    Signals (highest first), business-scoped, soft-deletes excluded, capped at 5:
    - ``same_proof`` (100): a schedule already references a screenshot whose bytes
      match this letter (an exact re-scan), on any unit.
    - ``same_buyer_and_schedule`` (75): a schedule on **another** unit whose buyer
      (normalized name) and installment set both match this letter.

    Read-only and never blocks the confirm — an empty list means nothing similar
    was found. Mirrors the Phase 9 transaction duplicate check.
    """
    _require_member(db, current_user.id, business_id)

    incoming_hash = _screenshot_hash_for_duplicate_check(
        db, business_id, payload.screenshot_id
    )
    incoming_buyer = _normalize_name(payload.buyer_name)
    incoming_plan = _installment_key(
        [(row.amount_minor, row.due_date) for row in payload.installments]
    )

    versions = (
        db.execute(
            select(ScheduleVersion)
            .where(
                ScheduleVersion.business_id == business_id,
                ScheduleVersion.deleted_at.is_(None),
            )
            .order_by(ScheduleVersion.created_at.desc())
        )
        .scalars()
        .all()
    )
    if not versions:
        return []

    units_by_id = {
        unit.id: unit
        for unit in db.execute(
            select(Unit).where(Unit.business_id == business_id, Unit.deleted_at.is_(None))
        )
        .scalars()
        .all()
    }
    counterparties_by_id = {
        cp.id: cp
        for cp in db.execute(
            select(Counterparty).where(
                Counterparty.business_id == business_id,
                Counterparty.deleted_at.is_(None),
            )
        )
        .scalars()
        .all()
    }
    plan_by_version: dict[uuid.UUID, list[tuple[int, date]]] = {}
    for inst in (
        db.execute(
            select(Installment).where(
                Installment.business_id == business_id,
                Installment.deleted_at.is_(None),
            )
        )
        .scalars()
        .all()
    ):
        plan_by_version.setdefault(inst.schedule_version_id, []).append(
            (inst.amount_minor, inst.due_date)
        )

    # Versions whose linked proof image's bytes match the incoming letter.
    proof_version_ids: set[uuid.UUID] = set()
    if incoming_hash:
        matching_screenshot_ids = set(
            db.execute(
                select(Screenshot.id).where(
                    Screenshot.business_id == business_id,
                    Screenshot.deleted_at.is_(None),
                    Screenshot.image_sha256 == incoming_hash,
                )
            )
            .scalars()
            .all()
        )
        proof_version_ids = {
            version.id
            for version in versions
            if version.offer_letter_screenshot_id in matching_screenshot_ids
        }

    out: list[ScheduleDuplicateCandidateOut] = []
    for version in versions:
        unit = units_by_id.get(version.unit_id)
        if unit is None:  # unit soft-deleted — not a live duplicate to surface
            continue
        counterparty = (
            counterparties_by_id.get(unit.buyer_counterparty_id)
            if unit.buyer_counterparty_id is not None
            else None
        )
        plan = plan_by_version.get(version.id, [])

        reasons: set[str] = set()
        score = 0
        if version.id in proof_version_ids:
            reasons.add("same_proof")
            score += SAME_PROOF_SCORE
        is_other_unit = (
            payload.exclude_unit_id is None
            or version.unit_id != payload.exclude_unit_id
        )
        candidate_buyer = counterparty.normalized_name if counterparty else None
        if (
            is_other_unit
            and incoming_buyer is not None
            and incoming_plan
            and candidate_buyer == incoming_buyer
            and _installment_key(plan) == incoming_plan
        ):
            reasons.add("same_buyer_and_schedule")
            score += SAME_BUYER_AND_SCHEDULE_SCORE

        if not reasons:
            continue
        out.append(
            ScheduleDuplicateCandidateOut(
                unit_id=version.unit_id,
                project_id=unit.project_id,
                unit_label=unit.label,
                buyer_name=counterparty.name if counterparty else None,
                schedule_version_id=version.id,
                version_no=version.version_no,
                installment_count=len(plan),
                total_minor=sum(amount for amount, _ in plan),
                reasons=sorted(reasons),
                score=score,
            )
        )

    # Stable: equal scores keep created_at-desc order (versions already sorted).
    out.sort(key=lambda candidate: candidate.score, reverse=True)
    return out[:MAX_DUPLICATE_CANDIDATES]


# Re-exported for main.py:
__all__ = ["router"]
