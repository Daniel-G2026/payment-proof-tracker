from __future__ import annotations

import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.db.models import Business, BusinessMember, CounterpartyType
from app.db.models.counterparty_type import normalize_counterparty_type_name
from app.schemas.counterparty_type import (
    CounterpartyTypeCreate,
    CounterpartyTypeOut,
    CounterpartyTypeUpdate,
)
from app.services.search import escape_like

router = APIRouter(
    prefix="/businesses/{business_id}/counterparty-types",
    tags=["counterparty-types"],
)

# Audit events are intentionally skipped here, same as categories: these are
# low-stakes picker labels (no money, no proof). The audit table is reserved
# for transactions/counterparties.


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


def _get_type(
    db: Session,
    business_id: uuid.UUID,
    type_id: uuid.UUID,
) -> CounterpartyType:
    row = db.get(CounterpartyType, type_id)
    if row is None or row.business_id != business_id or row.deleted_at is not None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Person type not found.",
        )
    return row


@router.post("", response_model=CounterpartyTypeOut, status_code=status.HTTP_200_OK)
def create_counterparty_type(
    business_id: uuid.UUID,
    payload: CounterpartyTypeCreate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> CounterpartyTypeOut:
    _require_member(db, current_user.id, business_id)

    existing = db.get(CounterpartyType, payload.id)
    if existing is not None:
        if existing.business_id != business_id or existing.deleted_at is not None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Person type not found.",
            )
        return CounterpartyTypeOut.model_validate(existing)

    row = CounterpartyType(
        id=payload.id,
        business_id=business_id,
        name=payload.name.strip(),
        normalized_name=normalize_counterparty_type_name(payload.name),
        is_archived=False,
    )
    db.add(row)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        winner = db.get(CounterpartyType, payload.id)
        if winner is None or winner.business_id != business_id or winner.deleted_at is not None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Person type not found.",
            ) from None
        return CounterpartyTypeOut.model_validate(winner)
    db.refresh(row)
    return CounterpartyTypeOut.model_validate(row)


@router.get("", response_model=list[CounterpartyTypeOut])
def list_counterparty_types(
    business_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
    search: str | None = None,
) -> list[CounterpartyTypeOut]:
    _require_member(db, current_user.id, business_id)
    stmt = select(CounterpartyType).where(
        CounterpartyType.business_id == business_id,
        CounterpartyType.deleted_at.is_(None),
    )
    if search:
        cleaned = search.strip()
        like = f"%{escape_like(cleaned)}%"
        normalized_like = f"%{escape_like(normalize_counterparty_type_name(cleaned))}%"
        stmt = stmt.where(
            or_(
                CounterpartyType.name.ilike(like, escape="\\"),
                CounterpartyType.normalized_name.ilike(normalized_like, escape="\\"),
            )
        )
    stmt = stmt.order_by(
        CounterpartyType.normalized_name.asc(), CounterpartyType.created_at.asc()
    )
    return [CounterpartyTypeOut.model_validate(row) for row in db.execute(stmt).scalars().all()]


@router.get("/{type_id}", response_model=CounterpartyTypeOut)
def get_counterparty_type(
    business_id: uuid.UUID,
    type_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> CounterpartyTypeOut:
    _require_member(db, current_user.id, business_id)
    return CounterpartyTypeOut.model_validate(_get_type(db, business_id, type_id))


@router.patch("/{type_id}", response_model=CounterpartyTypeOut)
def update_counterparty_type(
    business_id: uuid.UUID,
    type_id: uuid.UUID,
    payload: CounterpartyTypeUpdate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> CounterpartyTypeOut:
    _require_member(db, current_user.id, business_id)
    row = _get_type(db, business_id, type_id)
    data = payload.model_dump(exclude_unset=True)
    expected_updated_at = data.pop("expected_updated_at", None)
    if expected_updated_at is not None and row.updated_at != expected_updated_at:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This record was updated elsewhere. Reload and try again.",
        )
    if "name" in data:
        row.name = data["name"].strip()
        row.normalized_name = normalize_counterparty_type_name(data["name"])
    if "is_archived" in data:
        row.is_archived = data["is_archived"]
    db.commit()
    db.refresh(row)
    return CounterpartyTypeOut.model_validate(row)


@router.delete("/{type_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_counterparty_type(
    business_id: uuid.UUID,
    type_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> None:
    _require_member(db, current_user.id, business_id)
    row = _get_type(db, business_id, type_id)
    row.deleted_at = datetime.now(UTC)
    db.commit()
