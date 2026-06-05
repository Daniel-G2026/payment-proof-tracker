from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict


class AuditEventOut(BaseModel):
    """One recorded change to an entity. `old_values`/`new_values` hold the
    field-level diff (Phase 10): on update only the changed fields, on create
    the created snapshot in new_values, on delete the pre-delete snapshot in
    old_values."""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    business_id: uuid.UUID
    actor_id: uuid.UUID
    entity_type: str
    entity_id: uuid.UUID | None
    action: str
    status: str
    old_values: dict[str, Any] | None
    new_values: dict[str, Any] | None
    created_at: datetime
