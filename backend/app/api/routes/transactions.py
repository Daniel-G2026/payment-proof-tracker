from __future__ import annotations

import uuid
from datetime import date, datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.db.models import Business, BusinessMember, Transaction
from app.schemas.transaction import TransactionCreate, TransactionOut, TransactionUpdate

router = APIRouter(
    prefix="/businesses/{business_id}/transactions",
    tags=["transactions"],
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


def _get_transaction(db: Session, business_id: uuid.UUID, transaction_id: uuid.UUID) -> Transaction:
    transaction = db.get(Transaction, transaction_id)
    if (
        transaction is None
        or transaction.business_id != business_id
        or transaction.deleted_at is not None
    ):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Transaction not found.",
        )
    return transaction


@router.post("", response_model=TransactionOut, status_code=status.HTTP_200_OK)
def create_transaction(
    business_id: uuid.UUID,
    payload: TransactionCreate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> TransactionOut:
    _require_member(db, current_user.id, business_id)

    existing = db.get(Transaction, payload.id)
    if existing is not None:
        if existing.business_id != business_id or existing.deleted_at is not None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Transaction not found.",
            )
        return TransactionOut.model_validate(existing)

    transaction = Transaction(
        id=payload.id,
        business_id=business_id,
        amount_minor=payload.amount_minor,
        currency=payload.currency,
        direction=payload.direction,
        transaction_date=payload.transaction_date,
        counterparty_name=payload.counterparty_name,
        category=payload.category,
        note=payload.note,
        bank_name=payload.bank_name,
        reference_number=payload.reference_number,
        source="manual",
        status="confirmed",
        created_by=current_user.id,
    )
    db.add(transaction)
    db.commit()
    db.refresh(transaction)
    return TransactionOut.model_validate(transaction)


@router.get("", response_model=list[TransactionOut])
def list_transactions(
    business_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
    direction: str | None = Query(default=None, pattern="^(money_in|money_out)$"),
    search: str | None = None,
    start_date: date | None = None,
    end_date: date | None = None,
) -> list[TransactionOut]:
    _require_member(db, current_user.id, business_id)
    stmt = select(Transaction).where(
        Transaction.business_id == business_id,
        Transaction.deleted_at.is_(None),
    )
    if direction is not None:
        stmt = stmt.where(Transaction.direction == direction)
    if start_date is not None:
        stmt = stmt.where(Transaction.transaction_date >= start_date)
    if end_date is not None:
        stmt = stmt.where(Transaction.transaction_date <= end_date)
    if search:
        like = f"%{search.strip()}%"
        stmt = stmt.where(
            or_(
                Transaction.counterparty_name.ilike(like),
                Transaction.category.ilike(like),
                Transaction.note.ilike(like),
                Transaction.bank_name.ilike(like),
                Transaction.reference_number.ilike(like),
            )
        )
    stmt = stmt.order_by(Transaction.transaction_date.desc(), Transaction.created_at.desc())
    return [TransactionOut.model_validate(row) for row in db.execute(stmt).scalars().all()]


@router.get("/{transaction_id}", response_model=TransactionOut)
def get_transaction(
    business_id: uuid.UUID,
    transaction_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> TransactionOut:
    _require_member(db, current_user.id, business_id)
    return TransactionOut.model_validate(_get_transaction(db, business_id, transaction_id))


@router.patch("/{transaction_id}", response_model=TransactionOut)
def update_transaction(
    business_id: uuid.UUID,
    transaction_id: uuid.UUID,
    payload: TransactionUpdate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> TransactionOut:
    _require_member(db, current_user.id, business_id)
    transaction = _get_transaction(db, business_id, transaction_id)
    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(transaction, field, value)
    db.commit()
    db.refresh(transaction)
    return TransactionOut.model_validate(transaction)


@router.delete("/{transaction_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_transaction(
    business_id: uuid.UUID,
    transaction_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> None:
    _require_member(db, current_user.id, business_id)
    transaction = _get_transaction(db, business_id, transaction_id)
    transaction.deleted_at = datetime.now(timezone.utc)
    db.commit()
