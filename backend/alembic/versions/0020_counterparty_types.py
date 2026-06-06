"""add counterparty_types table + seed defaults (and types already in use)

Revision ID: 0020_counterparty_types
Revises: 0019_seed_land_site_cost
Create Date: 2026-06-06

Promotes person types from an on-device-only metadata list to a first-class,
backend-synced entity (mirrors the categories vertical). The one-time backfill
seeds, per existing business, the four built-in defaults UNION the distinct
non-empty `type` values already on that business's counterparties — so any
custom type a user created before this change carries over. Deterministic
per-business seed ids (uuid5) keep it idempotent and let the on-device import
dedupe against these rows (a client create POST with the same id is an upsert).
"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op
from app.db.models.counterparty_type import (
    DEFAULT_COUNTERPARTY_TYPES,
    normalize_counterparty_type_name,
    seed_counterparty_type_id,
)

revision: str = "0020_counterparty_types"
down_revision: str | None = "0019_seed_land_site_cost"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _counterparty_types_table():
    return sa.table(
        "counterparty_types",
        sa.column("id", postgresql.UUID(as_uuid=True)),
        sa.column("business_id", postgresql.UUID(as_uuid=True)),
        sa.column("name", sa.Text()),
        sa.column("normalized_name", sa.Text()),
        sa.column("is_archived", sa.Boolean()),
    )


def upgrade() -> None:
    op.create_table(
        "counterparty_types",
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
    op.create_index(
        "ix_counterparty_types_business_id", "counterparty_types", ["business_id"]
    )
    op.create_index(
        "ix_counterparty_types_business_id_normalized_name",
        "counterparty_types",
        ["business_id", "normalized_name"],
    )

    # One-time backfill: per business, seed the built-in defaults UNION the
    # distinct types already on its counterparties. Deterministic per-business
    # ids (keyed by normalized name) dedupe the union and keep this re-runnable.
    bind = op.get_bind()
    business_ids = bind.execute(sa.text("SELECT id FROM businesses")).scalars().all()
    seed_rows: list[dict[str, object]] = []
    for business_id in business_ids:
        # normalized_name -> display name (the snake_case key the app stores).
        wanted: dict[str, str] = {}
        for default in DEFAULT_COUNTERPARTY_TYPES:
            normalized = normalize_counterparty_type_name(default)
            if normalized:
                wanted.setdefault(normalized, normalized)
        in_use = bind.execute(
            sa.text(
                "SELECT DISTINCT type FROM counterparties "
                "WHERE business_id = :b AND deleted_at IS NULL AND type <> ''"
            ),
            {"b": business_id},
        ).scalars().all()
        for raw in in_use:
            normalized = normalize_counterparty_type_name(raw or "")
            if normalized:
                wanted.setdefault(normalized, normalized)
        for normalized, name in wanted.items():
            seed_rows.append(
                {
                    "id": seed_counterparty_type_id(business_id, normalized),
                    "business_id": business_id,
                    "name": name,
                    "normalized_name": normalized,
                    "is_archived": False,
                }
            )
    if seed_rows:
        op.bulk_insert(_counterparty_types_table(), seed_rows)


def downgrade() -> None:
    op.drop_index(
        "ix_counterparty_types_business_id_normalized_name",
        table_name="counterparty_types",
    )
    op.drop_index("ix_counterparty_types_business_id", table_name="counterparty_types")
    op.drop_table("counterparty_types")
