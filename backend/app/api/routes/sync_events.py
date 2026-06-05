from __future__ import annotations

import uuid
from typing import Annotated

from fastapi import APIRouter, Body, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.api.routes.transactions import _require_member
from app.db.models import SyncEvent
from app.schemas.sync_event import SyncEventIn, SyncEventIngestResult, SyncEventOut

router = APIRouter(prefix="/businesses/{business_id}", tags=["sync-events"])

# Bound the batch so a device can't flush an unbounded report in one request.
MAX_BATCH = 200

DbSession = Annotated[Session, Depends(get_db)]


@router.post("/sync-events", response_model=SyncEventIngestResult)
def ingest_sync_events(
    business_id: uuid.UUID,
    current_user: CurrentUser,
    db: DbSession,
    events: list[SyncEventIn] = Body(..., max_length=MAX_BATCH),
) -> SyncEventIngestResult:
    """Idempotently ingest a batch of device-reported sync attempts.

    Keyed by the device-generated id, so a re-flushed batch (the device didn't
    get our response, common on flaky links) is deduped instead of doubling."""
    _require_member(db, current_user.id, business_id)

    accepted = 0
    duplicates = 0
    seen: set[uuid.UUID] = set()
    for event in events:
        # Guard against a batch that repeats an id within itself.
        if event.id in seen or db.get(SyncEvent, event.id) is not None:
            duplicates += 1
            continue
        seen.add(event.id)
        db.add(
            SyncEvent(
                id=event.id,
                business_id=business_id,
                actor_id=current_user.id,
                entity_type=event.entity_type,
                entity_id=event.entity_id,
                action=event.action,
                status=event.status,
                error_message=event.error_message,
                occurred_at=event.occurred_at,
            )
        )
        accepted += 1
    db.commit()
    return SyncEventIngestResult(accepted=accepted, duplicates=duplicates)


@router.get("/sync-events", response_model=list[SyncEventOut])
def list_sync_events(
    business_id: uuid.UUID,
    current_user: CurrentUser,
    db: DbSession,
    status: str | None = Query(default=None, max_length=32),
    limit: int = Query(default=100, ge=1, le=500),
) -> list[SyncEventOut]:
    """Central inspection of reported sync attempts, newest-first."""
    _require_member(db, current_user.id, business_id)
    stmt = select(SyncEvent).where(SyncEvent.business_id == business_id)
    if status is not None:
        stmt = stmt.where(SyncEvent.status == status)
    stmt = stmt.order_by(SyncEvent.occurred_at.desc(), SyncEvent.id.desc()).limit(limit)
    return [SyncEventOut.model_validate(row) for row in db.execute(stmt).scalars().all()]
