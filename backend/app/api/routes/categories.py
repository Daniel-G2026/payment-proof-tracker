from __future__ import annotations

import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.db.models import Business, BusinessMember, Category
from app.db.models.category import normalize_category_name
from app.schemas.category import CategoryCreate, CategoryOut, CategoryUpdate
from app.services.search import escape_like

router = APIRouter(
    prefix="/businesses/{business_id}/categories",
    tags=["categories"],
)

# Audit events are intentionally skipped for categories in v1: they are
# low-stakes labels (no money, no proof) and the audit table is reserved for
# transactions/counterparties. Revisit if categories ever carry real data.


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


def _get_category(
    db: Session,
    business_id: uuid.UUID,
    category_id: uuid.UUID,
) -> Category:
    category = db.get(Category, category_id)
    if category is None or category.business_id != business_id or category.deleted_at is not None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Category not found.",
        )
    return category


@router.post("", response_model=CategoryOut, status_code=status.HTTP_200_OK)
def create_category(
    business_id: uuid.UUID,
    payload: CategoryCreate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> CategoryOut:
    _require_member(db, current_user.id, business_id)

    existing = db.get(Category, payload.id)
    if existing is not None:
        if existing.business_id != business_id or existing.deleted_at is not None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Category not found.",
            )
        return CategoryOut.model_validate(existing)

    category = Category(
        id=payload.id,
        business_id=business_id,
        name=payload.name.strip(),
        normalized_name=normalize_category_name(payload.name),
        is_archived=False,
    )
    db.add(category)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        winner = db.get(Category, payload.id)
        if winner is None or winner.business_id != business_id or winner.deleted_at is not None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Category not found.",
            ) from None
        return CategoryOut.model_validate(winner)
    db.refresh(category)
    return CategoryOut.model_validate(category)


@router.get("", response_model=list[CategoryOut])
def list_categories(
    business_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
    search: str | None = None,
) -> list[CategoryOut]:
    _require_member(db, current_user.id, business_id)
    stmt = select(Category).where(
        Category.business_id == business_id,
        Category.deleted_at.is_(None),
    )
    if search:
        cleaned = search.strip()
        like = f"%{escape_like(cleaned)}%"
        normalized_like = f"%{escape_like(normalize_category_name(cleaned))}%"
        stmt = stmt.where(
            or_(
                Category.name.ilike(like, escape="\\"),
                Category.normalized_name.ilike(normalized_like, escape="\\"),
            )
        )
    stmt = stmt.order_by(Category.normalized_name.asc(), Category.created_at.asc())
    return [CategoryOut.model_validate(row) for row in db.execute(stmt).scalars().all()]


@router.get("/{category_id}", response_model=CategoryOut)
def get_category(
    business_id: uuid.UUID,
    category_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> CategoryOut:
    _require_member(db, current_user.id, business_id)
    return CategoryOut.model_validate(_get_category(db, business_id, category_id))


@router.patch("/{category_id}", response_model=CategoryOut)
def update_category(
    business_id: uuid.UUID,
    category_id: uuid.UUID,
    payload: CategoryUpdate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> CategoryOut:
    _require_member(db, current_user.id, business_id)
    category = _get_category(db, business_id, category_id)
    data = payload.model_dump(exclude_unset=True)
    expected_updated_at = data.pop("expected_updated_at", None)
    if expected_updated_at is not None and category.updated_at != expected_updated_at:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This record was updated elsewhere. Reload and try again.",
        )
    if "name" in data:
        category.name = data["name"].strip()
        category.normalized_name = normalize_category_name(data["name"])
    if "is_archived" in data:
        category.is_archived = data["is_archived"]
    db.commit()
    db.refresh(category)
    return CategoryOut.model_validate(category)


@router.delete("/{category_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_category(
    business_id: uuid.UUID,
    category_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> None:
    _require_member(db, current_user.id, business_id)
    category = _get_category(db, business_id, category_id)
    category.deleted_at = datetime.now(UTC)
    db.commit()
