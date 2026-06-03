import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import JSON, DateTime, ForeignKey, String, Text, Uuid, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base

# Generic JSON in the Python model so SQLite tests share the same metadata;
# Postgres deployments get JSONB via the variant. Mirrors transaction_draft.
_JsonType = JSON().with_variant(JSONB(), "postgresql")


class AuditEvent(Base):
    __tablename__ = "audit_events"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    business_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("businesses.id"), nullable=False, index=True
    )
    actor_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("profiles.id"), nullable=False, index=True
    )
    entity_type: Mapped[str] = mapped_column(String(length=64), nullable=False, index=True)
    entity_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, nullable=True, index=True)
    action: Mapped[str] = mapped_column(String(length=64), nullable=False, index=True)
    status: Mapped[str] = mapped_column(String(length=32), nullable=False, index=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Field-level diff for edit-history (Phase 12). On update these hold only
    # the changed fields' before/after values; on create new_values is the
    # created snapshot; on delete old_values is the pre-delete snapshot.
    old_values: Mapped[dict[str, Any] | None] = mapped_column(_JsonType, nullable=True)
    new_values: Mapped[dict[str, Any] | None] = mapped_column(_JsonType, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False, index=True
    )
