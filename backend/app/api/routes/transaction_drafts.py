from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.api.routes.transactions import _require_member
from app.db.models import Screenshot, TransactionDraft
from app.schemas.transaction_draft import TransactionDraftConfirmIn, TransactionDraftOut
from app.services.audit import add_audit_event

router = APIRouter(tags=["transaction-drafts"])

DbSession = Annotated[Session, Depends(get_db)]


def _load_draft_by_screenshot(db: Session, screenshot_id: uuid.UUID) -> TransactionDraft | None:
    screenshot = db.get(Screenshot, screenshot_id)
    if screenshot is None or screenshot.deleted_at is not None:
        return None
    # The relationship is uselist=False, so this is at most one row.
    return (
        db.query(TransactionDraft)
        .filter(TransactionDraft.screenshot_id == screenshot_id)
        .one_or_none()
    )


@router.get("/screenshots/{screenshot_id}/draft", response_model=TransactionDraftOut)
def get_screenshot_draft(
    screenshot_id: uuid.UUID,
    current_user: CurrentUser,
    db: DbSession,
) -> TransactionDraftOut:
    """Poll endpoint. 404 means the worker hasn't created the row yet — the
    client should keep polling for up to ~60s. 200 returns the latest state
    (queued / processing / ready / failed)."""
    screenshot = db.get(Screenshot, screenshot_id)
    if screenshot is None or screenshot.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Screenshot not found.")
    _require_member(db, current_user.id, screenshot.business_id)
    draft = _load_draft_by_screenshot(db, screenshot_id)
    if draft is None:
        raise HTTPException(status_code=404, detail="Draft not ready.")
    return TransactionDraftOut.model_validate(draft)


@router.post(
    "/transaction-drafts/{draft_id}/confirm",
    response_model=TransactionDraftOut,
)
def confirm_draft(
    draft_id: uuid.UUID,
    payload: TransactionDraftConfirmIn,
    current_user: CurrentUser,
    db: DbSession,
) -> TransactionDraftOut:
    """Telemetry: client tells us which saved transaction the user produced
    from this draft. Lets us measure acceptance rate without instrumenting
    the form. Idempotent — second call is a no-op."""
    draft = db.get(TransactionDraft, draft_id)
    if draft is None:
        raise HTTPException(status_code=404, detail="Draft not found.")
    _require_member(db, current_user.id, draft.business_id)
    # We don't currently link the confirmed transaction_id onto the draft row
    # (no FK column for it) — just stamp the timestamp. If we want the
    # back-reference later, that's an additive migration.
    _ = payload.transaction_id
    if draft.confirmed_at is None:
        draft.confirmed_at = datetime.now(tz=UTC)
        add_audit_event(
            db,
            business_id=draft.business_id,
            actor_id=current_user.id,
            entity_type="transaction_draft",
            entity_id=draft.id,
            action="confirm",
            status="success",
        )
        db.commit()
        db.refresh(draft)
    return TransactionDraftOut.model_validate(draft)


# Re-exported for main.py:
__all__ = ["router"]
