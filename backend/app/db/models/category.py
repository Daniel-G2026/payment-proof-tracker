import re
import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Text, Uuid, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class Category(Base):
    __tablename__ = "categories"

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


# Niche-first: the categories table is generic, but the project ships with a
# construction-business default list so the picker is never an empty box.
# See memory `niche-first-construction` / `structured-categories-plan`.
# Kept deliberately short: a small, broadly-applicable starter set beats a long
# list that clutters every form. Situational categories (cement, transport,
# fuel, rent, permits & fees, etc.) are left out on purpose — the user adds them
# from the form when needed, and they stick for next time.
DEFAULT_CONSTRUCTION_CATEGORIES: tuple[str, ...] = (
    "Materials",
    "Labour",
    "Wages",
    "Equipment & tools",
    "Subcontractor",
    "Client payment",
    "Miscellaneous",
)

# Namespace for deriving stable per-business seed UUIDs. Deterministic ids keep
# seeding idempotent: re-running the backfill (or seeding a business that was
# already seeded) collides on the primary key instead of inserting duplicates.
_SEED_NAMESPACE = uuid.UUID("6f1d0b8e-2a4c-4b7e-9f3a-1c2d3e4f5a6b")


def normalize_category_name(name: str) -> str:
    return re.sub(r"\s+", " ", name.strip().lower())


def _seed_category_id(business_id: uuid.UUID, normalized_name: str) -> uuid.UUID:
    return uuid.uuid5(_SEED_NAMESPACE, f"{business_id}:{normalized_name}")


def default_category_seed(business_id: uuid.UUID) -> list[dict[str, object]]:
    """Default category rows for a business, as plain dicts.

    Returned as dicts (not ORM instances) so the same helper feeds both the
    create-business route and the raw-SQL Alembic backfill.
    """
    rows: list[dict[str, object]] = []
    for name in DEFAULT_CONSTRUCTION_CATEGORIES:
        normalized = normalize_category_name(name)
        rows.append(
            {
                "id": _seed_category_id(business_id, normalized),
                "business_id": business_id,
                "name": name,
                "normalized_name": normalized,
                "is_archived": False,
            }
        )
    return rows
