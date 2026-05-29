from __future__ import annotations

import uuid

from sqlalchemy.orm import Session

from app.db.models import AuditEvent


def add_audit_event(
    db: Session,
    *,
    business_id: uuid.UUID,
    actor_id: uuid.UUID,
    entity_type: str,
    entity_id: uuid.UUID | None,
    action: str,
    status: str,
    error_message: str | None = None,
) -> AuditEvent:
    event = AuditEvent(
        business_id=business_id,
        actor_id=actor_id,
        entity_type=entity_type,
        entity_id=entity_id,
        action=action,
        status=status,
        error_message=error_message,
    )
    db.add(event)
    return event
