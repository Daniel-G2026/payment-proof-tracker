import uuid
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, ForeignKey, String, Text, Uuid, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base

# A unit's lifecycle status. Kept as a free string with a sane default rather
# than an enum so later cuts can add states without a migration. "available"
# (no buyer yet) → "reserved"/"sold" as a buyer + schedule are attached.
DEFAULT_UNIT_STATUS = "available"


class Unit(Base):
    """A single house/plot within a project.

    Has one buyer (a counterparty — no new person table; a buyer holding
    multiple units = multiple units pointing at the same counterparty) and,
    from Cut 2, one active versioned schedule. `total_price_minor` is the
    offer-letter headline price; per-installment amounts live on the schedule.
    """

    __tablename__ = "units"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    business_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("businesses.id"), nullable=False, index=True
    )
    project_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("projects.id"), nullable=False, index=True
    )
    label: Mapped[str] = mapped_column(Text, nullable=False)
    buyer_counterparty_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("counterparties.id"), nullable=True, index=True
    )
    total_price_minor: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    currency: Mapped[str] = mapped_column(String, nullable=False, default="NGN")
    status: Mapped[str] = mapped_column(String, nullable=False, default=DEFAULT_UNIT_STATUS)
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
