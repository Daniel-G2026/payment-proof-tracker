from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.db.models import Business, BusinessMember, Category
from app.db.models.category import default_category_seed
from app.schemas.business import BusinessCreate, BusinessOut, BusinessUpdate

router = APIRouter(prefix="/businesses", tags=["businesses"])


def _require_member(db: Session, profile_id: uuid.UUID, business_id: uuid.UUID) -> Business:
    """Look up a business and confirm the user is a member. 404 on either miss
    (do not leak existence)."""
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
    return business


def _require_owner(db: Session, profile_id: uuid.UUID, business_id: uuid.UUID) -> Business:
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
    if membership.role != "owner":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Business owner access required.",
        )
    return business


@router.post("", response_model=BusinessOut, status_code=status.HTTP_200_OK)
def create_business(
    payload: BusinessCreate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> BusinessOut:
    """Upsert-by-id. Client supplies the UUID so retries are idempotent.

    If the row already exists and the caller owns it, return it unchanged.
    If it exists but the caller is not a member, return 404 (no leakage).
    """
    existing = db.get(Business, payload.id)
    if existing is not None:
        # Confirm membership; raises 404 if not.
        _require_member(db, current_user.id, existing.id)
        return BusinessOut.model_validate(existing)

    business = Business(
        id=payload.id,
        owner_id=current_user.id,
        name=payload.name,
        country=payload.country,
        currency=payload.currency,
        business_type=payload.business_type,
    )
    membership = BusinessMember(
        business_id=business.id,
        user_id=current_user.id,
        role="owner",
    )
    db.add(business)
    db.add(membership)
    # Seed the construction default categories so the picker is never empty.
    # Stable per-business UUIDs keep this idempotent under retries.
    for row in default_category_seed(business.id):
        db.add(Category(**row))
    db.commit()
    db.refresh(business)
    return BusinessOut.model_validate(business)


@router.get("", response_model=list[BusinessOut])
def list_my_businesses(
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> list[BusinessOut]:
    """Businesses where the caller is a member of any role."""
    stmt = (
        select(Business)
        .join(BusinessMember, BusinessMember.business_id == Business.id)
        .where(BusinessMember.user_id == current_user.id)
        .order_by(Business.created_at.asc())
    )
    rows = db.execute(stmt).scalars().all()
    return [BusinessOut.model_validate(b) for b in rows]


@router.get("/{business_id}", response_model=BusinessOut)
def get_business(
    business_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> BusinessOut:
    business = _require_member(db, current_user.id, business_id)
    return BusinessOut.model_validate(business)


@router.patch("/{business_id}", response_model=BusinessOut)
def update_business(
    business_id: uuid.UUID,
    payload: BusinessUpdate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> BusinessOut:
    business = _require_owner(db, current_user.id, business_id)
    data = payload.model_dump(exclude_unset=True)
    for field, value in data.items():
        setattr(business, field, value)
    db.commit()
    db.refresh(business)
    return BusinessOut.model_validate(business)
