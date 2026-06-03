from __future__ import annotations

import uuid
from datetime import date, datetime
from typing import Any

from sqlalchemy.orm import Session

from app.db.models import AuditEvent


def _json_safe(value: Any) -> Any:
    """Coerce a column value into something the JSON/JSONB column can store.

    UUIDs and dates aren't natively JSON-serializable; everything else the
    audited columns hold (str, int, None) passes through unchanged.
    """
    if isinstance(value, uuid.UUID):
        return str(value)
    if isinstance(value, (date, datetime)):
        return value.isoformat()
    return value


def _json_safe_dict(values: dict[str, Any] | None) -> dict[str, Any] | None:
    if values is None:
        return None
    return {key: _json_safe(val) for key, val in values.items()}


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
    old_values: dict[str, Any] | None = None,
    new_values: dict[str, Any] | None = None,
) -> AuditEvent:
    event = AuditEvent(
        business_id=business_id,
        actor_id=actor_id,
        entity_type=entity_type,
        entity_id=entity_id,
        action=action,
        status=status,
        error_message=error_message,
        old_values=_json_safe_dict(old_values),
        new_values=_json_safe_dict(new_values),
    )
    db.add(event)
    return event
