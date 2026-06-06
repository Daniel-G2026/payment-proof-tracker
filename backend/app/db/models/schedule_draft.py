import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import (
    JSON,
    BigInteger,
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
# the Alembic migration. Same pattern as transaction_draft.py.
_JsonType = JSON().with_variant(JSONB(), "postgresql")

from app.db.base import Base


class ScheduleDraft(Base):
    """Server-side AI extraction result for one offer-letter screenshot.

    The Cut 6 sibling of [TransactionDraft]: one draft per offer-letter
    screenshot (UNIQUE(screenshot_id)), status walks queued → processing →
    ready | failed. Cleaned per-field values (buyer_name, total_minor,
    currency_code, installments) live alongside per-field confidence; raw_json
    is the full vision-model response, purged after the retention window.

    The draft never writes schedule rows itself — the reviewed draft is turned
    into a real versioned schedule via the existing create-schedule endpoint
    (see docs/phase-13-offer-letter-contract.md §6–7).
    """

    __tablename__ = "schedule_drafts"

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

    buyer_name: Mapped[str | None] = mapped_column(String, nullable=True)
    total_minor: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    currency_code: Mapped[str | None] = mapped_column(String(8), nullable=True)
    # Cleaned installment rows: [{"sequence": int, "amount_minor": int,
    # "due_date": "YYYY-MM-DD"}]. Null when the array couldn't be read with
    # confidence — the user then enters rows manually.
    installments: Mapped[list[dict[str, Any]] | None] = mapped_column(_JsonType, nullable=True)

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
        "Screenshot", back_populates="schedule_draft"
    )


# Status values; importable so route/worker reference one source. Mirrors the
# transaction-draft status vocabulary.
SCHEDULE_DRAFT_STATUS_QUEUED = "queued"
SCHEDULE_DRAFT_STATUS_PROCESSING = "processing"
SCHEDULE_DRAFT_STATUS_READY = "ready"
SCHEDULE_DRAFT_STATUS_FAILED = "failed"
SCHEDULE_DRAFT_STATUS_RATE_LIMITED = "rate_limited"

SCHEDULE_DRAFT_ERROR_VISION = "vision_error"
SCHEDULE_DRAFT_ERROR_PARSE = "parse_error"
SCHEDULE_DRAFT_ERROR_TIMEOUT = "timeout"
SCHEDULE_DRAFT_ERROR_UNREADABLE = "unreadable"
