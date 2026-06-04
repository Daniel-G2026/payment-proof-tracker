"""add categories table + seed defaults for existing businesses

Revision ID: 0011_categories
Revises: 0010_audit_event_values
Create Date: 2026-06-04

"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op
from app.db.models.category import default_category_seed

revision: str = "0011_categories"
down_revision: str | None = "0010_audit_event_values"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "categories",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "business_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("businesses.id"),
            nullable=False,
        ),
        sa.Column("name", sa.Text(), nullable=False),
        sa.Column("normalized_name", sa.Text(), nullable=False),
        sa.Column(
            "is_archived",
            sa.Boolean(),
            server_default=sa.false(),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("ix_categories_business_id", "categories", ["business_id"])
    op.create_index(
        "ix_categories_business_id_normalized_name",
        "categories",
        ["business_id", "normalized_name"],
    )

    # One-time backfill: seed the construction defaults for every existing
    # business that has no categories yet. Stable per-business UUIDs make this
    # safe to re-run. created_at/updated_at fall to their server defaults.
    bind = op.get_bind()
    categories = sa.table(
        "categories",
        sa.column("id", postgresql.UUID(as_uuid=True)),
        sa.column("business_id", postgresql.UUID(as_uuid=True)),
        sa.column("name", sa.Text()),
        sa.column("normalized_name", sa.Text()),
        sa.column("is_archived", sa.Boolean()),
    )
    business_ids = bind.execute(sa.text("SELECT id FROM businesses")).scalars().all()
    seed_rows: list[dict[str, object]] = []
    for business_id in business_ids:
        seed_rows.extend(default_category_seed(business_id))
    if seed_rows:
        op.bulk_insert(categories, seed_rows)


def downgrade() -> None:
    op.drop_index("ix_categories_business_id_normalized_name", table_name="categories")
    op.drop_index("ix_categories_business_id", table_name="categories")
    op.drop_table("categories")
