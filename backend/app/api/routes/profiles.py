from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.db.models import Profile
from app.schemas.profile import ProfileOut, ProfileUpdate

router = APIRouter(tags=["profile"])


@router.get("/me", response_model=ProfileOut)
def get_me(current_user: CurrentUser) -> ProfileOut:
    return ProfileOut.model_validate(current_user)


@router.patch("/me", response_model=ProfileOut)
def update_me(
    payload: ProfileUpdate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> ProfileOut:
    # Re-fetch in the route's session so we never mutate a detached instance.
    profile = db.get(Profile, current_user.id)
    if profile is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Profile not found.")
    data = payload.model_dump(exclude_unset=True)
    for field, value in data.items():
        setattr(profile, field, value)
    db.commit()
    db.refresh(profile)
    return ProfileOut.model_validate(profile)
