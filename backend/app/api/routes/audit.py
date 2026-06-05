from __future__ import annotations

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.api.routes.transactions import _require_member
from app.db.models import AuditEvent
from app.schemas.audit import AuditEventOut

router = APIRouter(prefix="/businesses/{business_id}", tags=["audit"])

# Only the lifecycle actions that make up an entity's edit history — excludes
# advisory noise like `duplicate_check`. Statuses are filtered to "success"
# so failed sync attempts don't appear as edits.
HISTORY_ACTIONS = ("create", "update", "delete")

DbSession = Annotated[Session, Depends(get_db)]


@router.get("/audit-events", response_model=list[AuditEventOut])
def list_audit_events(
    business_id: uuid.UUID,
    current_user: CurrentUser,
    db: DbSession,
    entity_type: str = Query(..., min_length=1, max_length=64),
    entity_id: uuid.UUID = Query(...),
    limit: int = Query(default=200, ge=1, le=500),
) -> list[AuditEventOut]:
    """Ordered edit history (newest first) for one entity. Member-guarded and
    scoped to the business, so audit rows never leak across businesses."""
    _require_member(db, current_user.id, business_id)
    stmt = (
        select(AuditEvent)
        .where(
            AuditEvent.business_id == business_id,
            AuditEvent.entity_type == entity_type,
            AuditEvent.entity_id == entity_id,
            AuditEvent.status == "success",
            AuditEvent.action.in_(HISTORY_ACTIONS),
        )
        .order_by(AuditEvent.created_at.desc(), AuditEvent.id.desc())
        .limit(limit)
    )
    return [AuditEventOut.model_validate(row) for row in db.execute(stmt).scalars().all()]
