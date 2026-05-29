from __future__ import annotations

import re
import uuid
from datetime import UTC, date, datetime

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import case, func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.db.models import Business, BusinessMember, Counterparty, Transaction
from app.schemas.counterparty import (
    COUNTERPARTY_TYPE_PATTERN,
    CounterpartyCreate,
    CounterpartyOut,
    CounterpartySummaryOut,
    CounterpartyUpdate,
)

router = APIRouter(
    prefix="/businesses/{business_id}/counterparties",
    tags=["counterparties"],
)


def _normalize_name(name: str) -> str:
    return re.sub(r"\s+", " ", name.strip().lower())


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


def _get_counterparty(
    db: Session,
    business_id: uuid.UUID,
    counterparty_id: uuid.UUID,
) -> Counterparty:
    counterparty = db.get(Counterparty, counterparty_id)
    if (
        counterparty is None
        or counterparty.business_id != business_id
        or counterparty.deleted_at is not None
    ):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Counterparty not found.",
        )
    return counterparty


@router.post("", response_model=CounterpartyOut, status_code=status.HTTP_200_OK)
def create_counterparty(
    business_id: uuid.UUID,
    payload: CounterpartyCreate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> CounterpartyOut:
    _require_member(db, current_user.id, business_id)

    existing = db.get(Counterparty, payload.id)
    if existing is not None:
        if existing.business_id != business_id or existing.deleted_at is not None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Counterparty not found.",
            )
        return CounterpartyOut.model_validate(existing)

    counterparty = Counterparty(
        id=payload.id,
        business_id=business_id,
        name=payload.name.strip(),
        normalized_name=_normalize_name(payload.name),
        type=payload.type,
        phone=payload.phone,
        notes=payload.notes,
    )
    db.add(counterparty)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        winner = db.get(Counterparty, payload.id)
        if (
            winner is None
            or winner.business_id != business_id
            or winner.deleted_at is not None
        ):
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Counterparty not found.",
            ) from None
        return CounterpartyOut.model_validate(winner)
    db.refresh(counterparty)
    return CounterpartyOut.model_validate(counterparty)


@router.get("", response_model=list[CounterpartyOut])
def list_counterparties(
    business_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
    search: str | None = None,
    type: str | None = Query(default=None, pattern=COUNTERPARTY_TYPE_PATTERN),
) -> list[CounterpartyOut]:
    _require_member(db, current_user.id, business_id)
    stmt = select(Counterparty).where(
        Counterparty.business_id == business_id,
        Counterparty.deleted_at.is_(None),
    )
    if type is not None:
        stmt = stmt.where(Counterparty.type == type)
    if search:
        cleaned = search.strip()
        like = f"%{cleaned}%"
        normalized_like = f"%{_normalize_name(cleaned)}%"
        stmt = stmt.where(
            or_(
                Counterparty.name.ilike(like),
                Counterparty.normalized_name.ilike(normalized_like),
            )
        )
    stmt = stmt.order_by(Counterparty.normalized_name.asc(), Counterparty.created_at.asc())
    return [CounterpartyOut.model_validate(row) for row in db.execute(stmt).scalars().all()]


@router.get("/{counterparty_id}", response_model=CounterpartyOut)
def get_counterparty(
    business_id: uuid.UUID,
    counterparty_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> CounterpartyOut:
    _require_member(db, current_user.id, business_id)
    return CounterpartyOut.model_validate(_get_counterparty(db, business_id, counterparty_id))


@router.patch("/{counterparty_id}", response_model=CounterpartyOut)
def update_counterparty(
    business_id: uuid.UUID,
    counterparty_id: uuid.UUID,
    payload: CounterpartyUpdate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> CounterpartyOut:
    _require_member(db, current_user.id, business_id)
    counterparty = _get_counterparty(db, business_id, counterparty_id)
    data = payload.model_dump(exclude_unset=True)
    if "name" in data:
        counterparty.name = data["name"].strip()
        counterparty.normalized_name = _normalize_name(data["name"])
    for field in ("type", "phone", "notes"):
        if field in data:
            setattr(counterparty, field, data[field])
    db.commit()
    db.refresh(counterparty)
    return CounterpartyOut.model_validate(counterparty)


@router.delete("/{counterparty_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_counterparty(
    business_id: uuid.UUID,
    counterparty_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> None:
    _require_member(db, current_user.id, business_id)
    counterparty = _get_counterparty(db, business_id, counterparty_id)
    counterparty.deleted_at = datetime.now(UTC)
    db.commit()


@router.get("/{counterparty_id}/summary", response_model=CounterpartySummaryOut)
def get_counterparty_summary(
    business_id: uuid.UUID,
    counterparty_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> CounterpartySummaryOut:
    _require_member(db, current_user.id, business_id)
    _get_counterparty(db, business_id, counterparty_id)
    total_in_expr = func.coalesce(
        func.sum(case((Transaction.direction == "money_in", Transaction.amount_minor), else_=0)),
        0,
    )
    total_out_expr = func.coalesce(
        func.sum(case((Transaction.direction == "money_out", Transaction.amount_minor), else_=0)),
        0,
    )
    stmt = select(
        total_in_expr,
        total_out_expr,
        func.max(Transaction.transaction_date),
        func.count(Transaction.id),
    ).where(
        Transaction.business_id == business_id,
        Transaction.counterparty_id == counterparty_id,
        Transaction.deleted_at.is_(None),
    )
    total_in, total_out, last_date, count = db.execute(stmt).one()
    return CounterpartySummaryOut(
        total_in=int(total_in or 0),
        total_out=int(total_out or 0),
        last_transaction_date=last_date if isinstance(last_date, date) else None,
        transaction_count=int(count or 0),
    )
