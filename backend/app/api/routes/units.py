from __future__ import annotations

import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.db.models import Business, BusinessMember, Counterparty, Project, Unit
from app.db.models.unit import DEFAULT_UNIT_STATUS
from app.schemas.unit import UnitCreate, UnitOut, UnitUpdate

router = APIRouter(
    prefix="/businesses/{business_id}/units",
    tags=["units"],
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


def _require_project(db: Session, business_id: uuid.UUID, project_id: uuid.UUID) -> None:
    project = db.get(Project, project_id)
    if project is None or project.business_id != business_id or project.deleted_at is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Project not found.")


def _validate_buyer(
    db: Session, business_id: uuid.UUID, buyer_counterparty_id: uuid.UUID | None
) -> None:
    if buyer_counterparty_id is None:
        return
    buyer = db.get(Counterparty, buyer_counterparty_id)
    if buyer is None or buyer.business_id != business_id or buyer.deleted_at is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Buyer not found.")


def _get_unit(db: Session, business_id: uuid.UUID, unit_id: uuid.UUID) -> Unit:
    unit = db.get(Unit, unit_id)
    if unit is None or unit.business_id != business_id or unit.deleted_at is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unit not found.")
    return unit


@router.post("", response_model=UnitOut, status_code=status.HTTP_200_OK)
def create_unit(
    business_id: uuid.UUID,
    payload: UnitCreate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> UnitOut:
    _require_member(db, current_user.id, business_id)

    existing = db.get(Unit, payload.id)
    if existing is not None:
        if existing.business_id != business_id or existing.deleted_at is not None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unit not found.")
        return UnitOut.model_validate(existing)

    _require_project(db, business_id, payload.project_id)
    _validate_buyer(db, business_id, payload.buyer_counterparty_id)

    unit = Unit(
        id=payload.id,
        business_id=business_id,
        project_id=payload.project_id,
        label=payload.label.strip(),
        buyer_counterparty_id=payload.buyer_counterparty_id,
        total_price_minor=payload.total_price_minor,
        currency=payload.currency,
        status=(payload.status.strip() if payload.status else DEFAULT_UNIT_STATUS),
    )
    db.add(unit)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        winner = db.get(Unit, payload.id)
        if winner is None or winner.business_id != business_id or winner.deleted_at is not None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="Unit not found."
            ) from None
        return UnitOut.model_validate(winner)
    db.refresh(unit)
    return UnitOut.model_validate(unit)


@router.get("", response_model=list[UnitOut])
def list_units(
    business_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
    project_id: uuid.UUID | None = None,
) -> list[UnitOut]:
    _require_member(db, current_user.id, business_id)
    stmt = select(Unit).where(Unit.business_id == business_id, Unit.deleted_at.is_(None))
    if project_id is not None:
        stmt = stmt.where(Unit.project_id == project_id)
    stmt = stmt.order_by(Unit.created_at.asc())
    return [UnitOut.model_validate(row) for row in db.execute(stmt).scalars().all()]


@router.get("/{unit_id}", response_model=UnitOut)
def get_unit(
    business_id: uuid.UUID,
    unit_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> UnitOut:
    _require_member(db, current_user.id, business_id)
    return UnitOut.model_validate(_get_unit(db, business_id, unit_id))


@router.patch("/{unit_id}", response_model=UnitOut)
def update_unit(
    business_id: uuid.UUID,
    unit_id: uuid.UUID,
    payload: UnitUpdate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> UnitOut:
    _require_member(db, current_user.id, business_id)
    unit = _get_unit(db, business_id, unit_id)
    data = payload.model_dump(exclude_unset=True)
    expected_updated_at = data.pop("expected_updated_at", None)
    if expected_updated_at is not None and unit.updated_at != expected_updated_at:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This record was updated elsewhere. Reload and try again.",
        )
    if "buyer_counterparty_id" in data:
        _validate_buyer(db, business_id, data["buyer_counterparty_id"])
        unit.buyer_counterparty_id = data["buyer_counterparty_id"]
    if "label" in data:
        unit.label = data["label"].strip()
    if "total_price_minor" in data:
        unit.total_price_minor = data["total_price_minor"]
    if "currency" in data and data["currency"] is not None:
        unit.currency = data["currency"]
    if "status" in data:
        unit.status = data["status"].strip()
    db.commit()
    db.refresh(unit)
    return UnitOut.model_validate(unit)


@router.delete("/{unit_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_unit(
    business_id: uuid.UUID,
    unit_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> None:
    _require_member(db, current_user.id, business_id)
    unit = _get_unit(db, business_id, unit_id)
    unit.deleted_at = datetime.now(UTC)
    db.commit()
