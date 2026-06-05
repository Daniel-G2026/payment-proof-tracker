import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, Text, Uuid, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class SyncEvent(Base):
    """A sync attempt reported by a device (Phase 12, deferred from Phase 10).

    Devices log every create/edit/delete sync attempt locally, then flush the
    unreported ones here when connectivity returns — so failed syncs can be
    inspected centrally. The primary key is the device-generated id, making
    ingest idempotent: a re-flushed batch collides instead of duplicating.

    `entity_id` is stored as free text (not a FK): it's device-reported
    telemetry that may reference rows that never reached the server.
    """

    __tablename__ = "sync_events"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    business_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("businesses.id"), nullable=False, index=True
    )
    actor_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("profiles.id"), nullable=False, index=True
    )
    entity_type: Mapped[str] = mapped_column(String(length=64), nullable=False, index=True)
    entity_id: Mapped[str | None] = mapped_column(String(length=64), nullable=True, index=True)
    action: Mapped[str] = mapped_column(String(length=64), nullable=False, index=True)
    status: Mapped[str] = mapped_column(String(length=32), nullable=False, index=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    # When the sync attempt happened on the device.
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    # When the backend received the report (may be much later — offline queue).
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
