"""Shared FastAPI dependencies."""

from __future__ import annotations

import uuid
from typing import Annotated

from fastapi import Depends, Header
from sqlalchemy.orm import Session

from app.core.auth import AuthError, verify_supabase_jwt
from app.db.models import Profile
from app.db.session import get_db

__all__ = ["get_db", "get_current_user", "CurrentUser"]


def _extract_bearer(authorization: str | None) -> str:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise AuthError("Missing Bearer token.")
    token = authorization.split(" ", 1)[1].strip()
    if not token:
        raise AuthError("Empty Bearer token.")
    return token


def get_current_user(
    authorization: Annotated[str | None, Header()] = None,
    db: Session = Depends(get_db),
) -> Profile:
    """Resolve the calling user from a Supabase JWT.

    Auto-upserts a Profile row keyed by the Supabase user id (`sub` claim).
    This is the single auth dependency every protected route uses; swapping
    providers later is a one-file change.
    """
    token = _extract_bearer(authorization)
    claims = verify_supabase_jwt(token)

    try:
        user_id = uuid.UUID(str(claims["sub"]))
    except (ValueError, KeyError) as exc:
        raise AuthError("Invalid subject in token.") from exc

    email = claims.get("email")

    profile = db.get(Profile, user_id)
    if profile is None:
        profile = Profile(id=user_id, email=email)
        db.add(profile)
        db.commit()
        db.refresh(profile)
    elif email and profile.email != email:
        # Keep email in sync with auth source of truth.
        profile.email = email
        db.commit()
        db.refresh(profile)

    return profile


CurrentUser = Annotated[Profile, Depends(get_current_user)]
