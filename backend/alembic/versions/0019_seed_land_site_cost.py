"""backfill the "Land / site cost" category for existing businesses (Phase 13 Cut 5)

Revision ID: 0019_seed_land_site_cost
Revises: 0018_transactions_earmark
Create Date: 2026-06-06

New businesses get this category automatically via the create-business seed
(it was added to DEFAULT_CONSTRUCTION_CATEGORIES). This backfills existing
businesses. Idempotent: skips any business that already has a non-deleted
category with this normalized name, and uses the deterministic per-business
seed id so it can't duplicate the create-business seed.
"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op
from app.db.models.category import (
    _seed_category_id,
    normalize_category_name,
)

revision: str = "0019_seed_land_site_cost"
down_revision: str | None = "0018_transactions_earmark"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_NAME = "Land / site cost"


def _categories_table():
    return sa.table(
        "categories",
        sa.column("id", postgresql.UUID(as_uuid=True)),
        sa.column("business_id", postgresql.UUID(as_uuid=True)),
        sa.column("name", sa.Text()),
        sa.column("normalized_name", sa.Text()),
        sa.column("is_archived", sa.Boolean()),
    )


def upgrade() -> None:
    bind = op.get_bind()
    normalized = normalize_category_name(_NAME)
    business_ids = bind.execute(sa.text("SELECT id FROM businesses")).scalars().all()

    rows: list[dict[str, object]] = []
    for business_id in business_ids:
        already = bind.execute(
            sa.text(
                "SELECT 1 FROM categories "
                "WHERE business_id = :b AND normalized_name = :n AND deleted_at IS NULL "
                "LIMIT 1"
            ),
            {"b": business_id, "n": normalized},
        ).first()
        if already is not None:
            continue
        rows.append(
            {
                "id": _seed_category_id(business_id, normalized),
                "business_id": business_id,
                "name": _NAME,
                "normalized_name": normalized,
                "is_archived": False,
            }
        )

    if rows:
        op.bulk_insert(_categories_table(), rows)


def downgrade() -> None:
    # Only remove the deterministically-seeded rows; never touch a category a
    # user created themselves (different id).
    bind = op.get_bind()
    normalized = normalize_category_name(_NAME)
    business_ids = bind.execute(sa.text("SELECT id FROM businesses")).scalars().all()
    seed_ids = [_seed_category_id(business_id, normalized) for business_id in business_ids]
    if seed_ids:
        bind.execute(
            sa.text("DELETE FROM categories WHERE id = ANY(:ids)"),
            {"ids": seed_ids},
        )
