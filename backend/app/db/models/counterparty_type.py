import re
import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Text, Uuid, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class CounterpartyType(Base):
    """The synced picker source for the *kinds of people* a business tracks
    (customer, supplier, subcontractor, employee, and any custom ones the user
    adds). A lighter sibling of [Category]: same offline-first sync shape, minus
    nothing extra. Counterparties keep storing their `type` as a plain string
    snapshot, so this table only feeds the picker — deleting a type never breaks
    an existing person. See memory `structured-categories-plan`.
    """

    __tablename__ = "counterparty_types"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    business_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("businesses.id"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(Text, nullable=False)
    normalized_name: Mapped[str] = mapped_column(Text, nullable=False)
    is_archived: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
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


# The four kinds every small business starts with, so the picker is never an
# empty box. Stored (and matched against counterparties.type) in the snake_case
# *key* form the app uses everywhere a type is persisted — the UI derives a
# Title-Case label for display. Custom types are added by the user and stick.
DEFAULT_COUNTERPARTY_TYPES: tuple[str, ...] = (
    "customer",
    "supplier",
    "subcontractor",
    "employee",
)

# Namespace for deriving stable per-business seed UUIDs. Distinct from the
# category namespace so a name shared across the two tables (e.g.
# "subcontractor") never collides. Deterministic ids keep seeding idempotent
# and let the on-device one-time import (Phase 13) dedupe against this backfill:
# the client recomputes the same id, so a create POST is an upsert, not a dup.
_SEED_NAMESPACE = uuid.UUID("7c9e6a41-3b2d-5f48-9a1c-8d6e0f2b4a73")


def normalize_counterparty_type_name(name: str) -> str:
    """Snake_case key form — matches the Android `normalize` exactly so the
    deterministic seed ids line up across backend and client."""
    return re.sub(r"[^a-z0-9]+", "_", name.strip().lower()).strip("_")


def seed_counterparty_type_id(business_id: uuid.UUID, normalized_name: str) -> uuid.UUID:
    return uuid.uuid5(_SEED_NAMESPACE, f"{business_id}:{normalized_name}")


def default_counterparty_type_seed(business_id: uuid.UUID) -> list[dict[str, object]]:
    """Default counterparty-type rows for a business, as plain dicts.

    Returned as dicts (not ORM instances) so the same helper feeds both the
    create-business route and the raw-SQL Alembic backfill.
    """
    rows: list[dict[str, object]] = []
    for name in DEFAULT_COUNTERPARTY_TYPES:
        normalized = normalize_counterparty_type_name(name)
        rows.append(
            {
                "id": seed_counterparty_type_id(business_id, normalized),
                "business_id": business_id,
                "name": normalized,
                "normalized_name": normalized,
                "is_archived": False,
            }
        )
    return rows
