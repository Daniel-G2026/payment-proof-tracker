import uuid
from datetime import datetime

from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    Uuid,
    func,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base

# Schedule sources.
SOURCE_MANUAL = "manual"
SOURCE_OFFER_LETTER = "offer_letter"


class ScheduleVersion(Base):
    """A versioned offer-letter schedule for a unit (Phase 13 Cut 2).

    Renegotiation creates a new active version; old versions are kept and
    viewable. **Exactly one active version per unit** is enforced, not promised:
    a partial unique index on ``unit_id WHERE is_active`` (backend), plus
    app-level "deactivate prior, activate new" in the same transaction.
    Installments hang off a version; fills/arrears are computed (never stored).
    """

    __tablename__ = "schedule_versions"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    business_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("businesses.id"), nullable=False, index=True
    )
    unit_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("units.id"), nullable=False, index=True
    )
    version_no: Mapped[int] = mapped_column(Integer, nullable=False)
    source: Mapped[str] = mapped_column(String, nullable=False, default=SOURCE_MANUAL)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    offer_letter_screenshot_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("screenshots.id"), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
        nullable=False,
    )
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    __table_args__ = (
        # One active schedule per unit — enforced. Partial unique index works on
        # both Postgres (prod) and SQLite (tests via create_all).
        Index(
            "uq_schedule_versions_unit_active",
            "unit_id",
            unique=True,
            postgresql_where=text("is_active"),
            sqlite_where=text("is_active"),
        ),
    )
