import uuid
from datetime import date, datetime
from typing import Any

from sqlalchemy import (
    JSON,
    BigInteger,
    Date,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    Uuid,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

# Generic JSON in the Python model so SQLite tests can use the same metadata;
# Postgres deployments get JSONB via the type variant + the explicit JSONB in
# the Alembic migration.
_JsonType = JSON().with_variant(JSONB(), "postgresql")

from app.db.base import Base


class TransactionDraft(Base):
    """Server-side OCR/AI extraction result for one screenshot.

    One draft per screenshot (UNIQUE(screenshot_id)) — re-extraction upserts in
    place. status walks queued → processing → ready | failed. Cleaned per-field
    values live alongside per-field confidence; raw_json is the full vision
    model response, purged after extraction_raw_retention_days.
    """

    __tablename__ = "transaction_drafts"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    screenshot_id: Mapped[uuid.UUID] = mapped_column(
        Uuid,
        ForeignKey("screenshots.id", ondelete="CASCADE"),
        nullable=False,
        unique=True,
    )
    business_id: Mapped[uuid.UUID] = mapped_column(
        Uuid,
        ForeignKey("businesses.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    status: Mapped[str] = mapped_column(String, nullable=False)

    amount_minor: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    currency_code: Mapped[str | None] = mapped_column(String(8), nullable=True)
    transaction_date: Mapped[date | None] = mapped_column(Date, nullable=True)
    reference_number: Mapped[str | None] = mapped_column(String, nullable=True)
    bank_name: Mapped[str | None] = mapped_column(String, nullable=True)
    counterparty_name: Mapped[str | None] = mapped_column(String, nullable=True)

    confidence: Mapped[dict[str, Any] | None] = mapped_column(_JsonType, nullable=True)
    raw_json: Mapped[dict[str, Any] | None] = mapped_column(_JsonType, nullable=True)

    error_code: Mapped[str | None] = mapped_column(String, nullable=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    model_version: Mapped[str | None] = mapped_column(String, nullable=True)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
        nullable=False,
    )
    confirmed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    screenshot: Mapped["Screenshot"] = relationship(  # noqa: F821 — forward ref resolved at runtime
        "Screenshot", back_populates="draft"
    )


# Valid status values; importable so route/worker code references a single source.
DRAFT_STATUS_QUEUED = "queued"
DRAFT_STATUS_PROCESSING = "processing"
DRAFT_STATUS_READY = "ready"
DRAFT_STATUS_FAILED = "failed"
DRAFT_STATUS_RATE_LIMITED = "rate_limited"

DRAFT_ERROR_VISION = "vision_error"
DRAFT_ERROR_PARSE = "parse_error"
DRAFT_ERROR_TIMEOUT = "timeout"
DRAFT_ERROR_UNREADABLE = "unreadable"
