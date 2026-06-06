from __future__ import annotations

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.api.routes.transactions import _require_member
from app.db.models import ScheduleDraft, Screenshot
from app.schemas.schedule_draft import ScheduleDraftOut

router = APIRouter(tags=["schedule-drafts"])

DbSession = Annotated[Session, Depends(get_db)]


def _load_schedule_draft(db: Session, screenshot_id: uuid.UUID) -> ScheduleDraft | None:
    screenshot = db.get(Screenshot, screenshot_id)
    if screenshot is None or screenshot.deleted_at is not None:
        return None
    # The relationship is uselist=False, so this is at most one row.
    return (
        db.query(ScheduleDraft)
        .filter(ScheduleDraft.screenshot_id == screenshot_id)
        .one_or_none()
    )


@router.get("/screenshots/{screenshot_id}/schedule-draft", response_model=ScheduleDraftOut)
def get_schedule_draft(
    screenshot_id: uuid.UUID,
    current_user: CurrentUser,
    db: DbSession,
) -> ScheduleDraftOut:
    """Poll endpoint for offer-letter extraction. 404 means the worker hasn't
    created the row yet — the client keeps polling for up to ~60s. 200 returns
    the latest state (queued / processing / ready / failed)."""
    screenshot = db.get(Screenshot, screenshot_id)
    if screenshot is None or screenshot.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Screenshot not found.")
    _require_member(db, current_user.id, screenshot.business_id)
    draft = _load_schedule_draft(db, screenshot_id)
    if draft is None:
        raise HTTPException(status_code=404, detail="Draft not ready.")
    return ScheduleDraftOut.model_validate(draft)


# Re-exported for main.py:
__all__ = ["router"]
