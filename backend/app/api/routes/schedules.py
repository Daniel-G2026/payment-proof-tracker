from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.db.models import Business, BusinessMember, Installment, ScheduleVersion, Unit
from app.schemas.schedule import (
    InstallmentOut,
    ScheduleVersionCreate,
    ScheduleVersionOut,
)

router = APIRouter(
    prefix="/businesses/{business_id}/units/{unit_id}/schedule-versions",
    tags=["schedules"],
)


def _require_member(db: Session, profile_id: uuid.UUID, business_id: uuid.UUID) -> None:
    business = db.get(Business, business_id)
    if business is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Business not found.")
    membership = db.execute(
        select(BusinessMember).where(
            BusinessMember.business_id == business_id,
            BusinessMember.user_id == profile_id,
        )
    ).scalar_one_or_none()
    if membership is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Business not found.")


def _require_unit(db: Session, business_id: uuid.UUID, unit_id: uuid.UUID) -> Unit:
    unit = db.get(Unit, unit_id)
    if unit is None or unit.business_id != business_id or unit.deleted_at is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unit not found.")
    return unit


def _installments_for(db: Session, version_id: uuid.UUID) -> list[Installment]:
    stmt = (
        select(Installment)
        .where(Installment.schedule_version_id == version_id, Installment.deleted_at.is_(None))
        .order_by(Installment.due_date.asc(), Installment.sequence.asc())
    )
    return list(db.execute(stmt).scalars().all())


def _to_out(db: Session, version: ScheduleVersion) -> ScheduleVersionOut:
    out = ScheduleVersionOut.model_validate(version)
    out.installments = [
        InstallmentOut.model_validate(row) for row in _installments_for(db, version.id)
    ]
    return out


@router.post("", response_model=ScheduleVersionOut, status_code=status.HTTP_200_OK)
def create_schedule_version(
    business_id: uuid.UUID,
    unit_id: uuid.UUID,
    payload: ScheduleVersionCreate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> ScheduleVersionOut:
    _require_member(db, current_user.id, business_id)
    _require_unit(db, business_id, unit_id)

    # Idempotent on the client-supplied id (re-POST returns the existing row).
    existing = db.get(ScheduleVersion, payload.id)
    if existing is not None:
        if existing.unit_id != unit_id or existing.deleted_at is not None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="Schedule version not found."
            )
        return _to_out(db, existing)

    # version_no = next after the unit's current max (history is preserved).
    max_version = db.execute(
        select(func.max(ScheduleVersion.version_no)).where(
            ScheduleVersion.unit_id == unit_id
        )
    ).scalar_one_or_none()
    next_version = (max_version or 0) + 1

    # Deactivate the currently-active version FIRST, then flush, so the partial
    # unique index (unit_id WHERE is_active) never sees two active rows at once.
    active = db.execute(
        select(ScheduleVersion).where(
            ScheduleVersion.unit_id == unit_id,
            ScheduleVersion.is_active.is_(True),
            ScheduleVersion.deleted_at.is_(None),
        )
    ).scalar_one_or_none()
    if active is not None:
        active.is_active = False
        db.flush()

    version = ScheduleVersion(
        id=payload.id,
        business_id=business_id,
        unit_id=unit_id,
        version_no=next_version,
        source=payload.source,
        is_active=True,
        offer_letter_screenshot_id=payload.offer_letter_screenshot_id,
    )
    db.add(version)
    db.flush()

    for line in payload.installments:
        db.add(
            Installment(
                id=line.id,
                business_id=business_id,
                schedule_version_id=version.id,
                sequence=line.sequence,
                amount_minor=line.amount_minor,
                due_date=line.due_date,
            )
        )
    db.commit()
    db.refresh(version)
    return _to_out(db, version)


@router.get("", response_model=list[ScheduleVersionOut])
def list_schedule_versions(
    business_id: uuid.UUID,
    unit_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> list[ScheduleVersionOut]:
    _require_member(db, current_user.id, business_id)
    _require_unit(db, business_id, unit_id)
    stmt = (
        select(ScheduleVersion)
        .where(ScheduleVersion.unit_id == unit_id, ScheduleVersion.deleted_at.is_(None))
        .order_by(ScheduleVersion.version_no.asc())
    )
    return [_to_out(db, row) for row in db.execute(stmt).scalars().all()]


@router.get("/active", response_model=ScheduleVersionOut)
def get_active_schedule_version(
    business_id: uuid.UUID,
    unit_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> ScheduleVersionOut:
    _require_member(db, current_user.id, business_id)
    _require_unit(db, business_id, unit_id)
    active = db.execute(
        select(ScheduleVersion).where(
            ScheduleVersion.unit_id == unit_id,
            ScheduleVersion.is_active.is_(True),
            ScheduleVersion.deleted_at.is_(None),
        )
    ).scalar_one_or_none()
    if active is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="No active schedule for this unit."
        )
    return _to_out(db, active)
