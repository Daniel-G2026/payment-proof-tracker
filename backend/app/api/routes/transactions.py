from __future__ import annotations

import re
import uuid
from datetime import UTC, date, datetime

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.db.models import Business, BusinessMember, Counterparty, Screenshot, Transaction
from app.schemas.transaction import (
    DuplicateCandidateIn,
    DuplicateCandidateOut,
    TransactionCreate,
    TransactionOut,
    TransactionUpdate,
)
from app.services.audit import add_audit_event

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


def _link_orphan_screenshot(
    db: Session,
    business_id: uuid.UUID,
    transaction_id: uuid.UUID,
    screenshot_id: uuid.UUID | None,
) -> None:
    """Phase 8.1: link a previously-uploaded orphan screenshot to a transaction.

    Atomic with the transaction insert/update — same Session, single commit.
    The status codes match the typical REST idiom:
      - 404 if the screenshot doesn't exist or is soft-deleted (don't leak
        existence of other businesses' rows).
      - 403 if it belongs to a different business than the transaction.
      - 409 if it's already linked to a different transaction (someone else
        claimed it first, or a client replay).

    Idempotent for re-linking to the SAME transaction (allows PATCH replay).
    """
    if screenshot_id is None:
        return
    screenshot = db.get(Screenshot, screenshot_id)
    if screenshot is None or screenshot.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Screenshot not found.")
    if screenshot.business_id != business_id:
        # Treating cross-business as 403 rather than 404 because the caller is
        # an authenticated member of business_id; this is intent-relevant info.
        raise HTTPException(
            status_code=403,
            detail="Screenshot belongs to a different business.",
        )
    if screenshot.transaction_id is not None and screenshot.transaction_id != transaction_id:
        raise HTTPException(
            status_code=409,
            detail="Screenshot is already linked to a different transaction.",
        )
    screenshot.transaction_id = transaction_id


def _validate_counterparty_id(
    db: Session,
    business_id: uuid.UUID,
    counterparty_id: uuid.UUID | None,
) -> None:
    if counterparty_id is None:
        return
    counterparty = db.get(Counterparty, counterparty_id)
    if (
        counterparty is None
        or counterparty.business_id != business_id
        or counterparty.deleted_at is not None
    ):
        raise HTTPException(
            status_code=422,
            detail="Counterparty is not valid for this business.",
        )


def _normalize_reference(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = re.sub(r"\s+", "", value).lower()
    return normalized or None


def _normalize_name(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = re.sub(r"\s+", " ", value.strip()).lower()
    return normalized or None


def _screenshot_hash_for_duplicate_check(
    db: Session,
    business_id: uuid.UUID,
    screenshot_id: uuid.UUID | None,
) -> str | None:
    if screenshot_id is None:
        return None
    screenshot = db.get(Screenshot, screenshot_id)
    if (
        screenshot is None
        or screenshot.business_id != business_id
        or screenshot.deleted_at is not None
    ):
        return None
    return screenshot.image_sha256


@router.post("/duplicate-candidates", response_model=list[DuplicateCandidateOut])
def duplicate_candidates(
    business_id: uuid.UUID,
    payload: DuplicateCandidateIn,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> list[DuplicateCandidateOut]:
    _require_member(db, current_user.id, business_id)
    incoming_hash = _screenshot_hash_for_duplicate_check(db, business_id, payload.screenshot_id)
    incoming_reference = _normalize_reference(payload.reference_number)
    incoming_name = _normalize_name(payload.counterparty_name)

    rows = db.execute(
        select(Transaction)
        .where(
            Transaction.business_id == business_id,
            Transaction.deleted_at.is_(None),
        )
        .order_by(Transaction.transaction_date.desc(), Transaction.created_at.desc())
    ).scalars().all()

    candidates: dict[uuid.UUID, tuple[Transaction, set[str], int]] = {}
    hashes_by_transaction: dict[uuid.UUID, set[str]] = {}
    if incoming_hash:
        screenshots = db.execute(
            select(Screenshot).where(
                Screenshot.business_id == business_id,
                Screenshot.deleted_at.is_(None),
                Screenshot.transaction_id.is_not(None),
                Screenshot.image_sha256 == incoming_hash,
            )
        ).scalars().all()
        for screenshot in screenshots:
            hashes_by_transaction.setdefault(screenshot.transaction_id, set()).add(
                screenshot.image_sha256
            )

    for row in rows:
        if (
            payload.exclude_transaction_id is not None
            and row.id == payload.exclude_transaction_id
        ):
            continue
        reasons: set[str] = set()
        score = 0
        if incoming_hash and incoming_hash in hashes_by_transaction.get(row.id, set()):
            reasons.add("same_proof")
            score += 100
        if incoming_reference and _normalize_reference(row.reference_number) == incoming_reference:
            reasons.add("same_reference")
            score += 80
        same_base = (
            row.amount_minor == payload.amount_minor
            and row.direction == payload.direction
            and row.transaction_date == payload.transaction_date
        )
        if (
            same_base
            and payload.counterparty_id is not None
            and row.counterparty_id == payload.counterparty_id
        ):
            reasons.add("same_amount_date_counterparty")
            score += 70
        elif (
            same_base
            and incoming_name
            and _normalize_name(row.counterparty_name) == incoming_name
        ):
            reasons.add("same_amount_date_name")
            score += 50
        if reasons:
            candidates[row.id] = (row, reasons, score)

    ranked = sorted(
        candidates.values(),
        key=lambda item: (item[2], item[0].transaction_date, item[0].created_at),
        reverse=True,
    )[:5]
    add_audit_event(
        db,
        business_id=business_id,
        actor_id=current_user.id,
        entity_type="transaction",
        entity_id=payload.exclude_transaction_id,
        action="duplicate_check",
        status="duplicate_found" if ranked else "no_duplicate",
    )
    db.commit()
    return [
        DuplicateCandidateOut(
            transaction=TransactionOut.model_validate(row),
            reasons=sorted(reasons),
            score=score,
        )
        for row, reasons, score in ranked
    ]


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

    _validate_counterparty_id(db, business_id, payload.counterparty_id)

    transaction = Transaction(
        id=payload.id,
        business_id=business_id,
        amount_minor=payload.amount_minor,
        currency=payload.currency,
        direction=payload.direction,
        transaction_date=payload.transaction_date,
        counterparty_id=payload.counterparty_id,
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
    try:
        db.flush()  # assign PK so the screenshot link references a real row
        _link_orphan_screenshot(db, business_id, transaction.id, payload.screenshot_id)
        add_audit_event(
            db,
            business_id=business_id,
            actor_id=current_user.id,
            entity_type="transaction",
            entity_id=transaction.id,
            action="create",
            status="success",
        )
        db.commit()
    except IntegrityError:
        db.rollback()
        winner = db.get(Transaction, payload.id)
        if (
            winner is None
            or winner.business_id != business_id
            or winner.deleted_at is not None
        ):
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Transaction not found.",
            ) from None
        return TransactionOut.model_validate(winner)
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
    data = payload.model_dump(exclude_unset=True)
    expected_updated_at = data.pop("expected_updated_at", None)
    if expected_updated_at is not None and transaction.updated_at != expected_updated_at:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This record was updated elsewhere. Reload and try again.",
        )
    if "counterparty_id" in data:
        _validate_counterparty_id(db, business_id, data["counterparty_id"])
    # screenshot_id is not a column on Transaction — it's a linking instruction.
    # Pop it out before the setattr loop, handle separately, atomic with commit.
    incoming_screenshot_id = data.pop("screenshot_id", None)
    for field, value in data.items():
        setattr(transaction, field, value)
    if incoming_screenshot_id is not None:
        _link_orphan_screenshot(db, business_id, transaction.id, incoming_screenshot_id)
    add_audit_event(
        db,
        business_id=business_id,
        actor_id=current_user.id,
        entity_type="transaction",
        entity_id=transaction.id,
        action="update",
        status="success",
    )
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
    transaction.deleted_at = datetime.now(UTC)
    add_audit_event(
        db,
        business_id=business_id,
        actor_id=current_user.id,
        entity_type="transaction",
        entity_id=transaction.id,
        action="delete",
        status="success",
    )
    db.commit()
