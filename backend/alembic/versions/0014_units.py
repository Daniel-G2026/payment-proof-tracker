"""add units table (Phase 13 Cut 1)

Revision ID: 0014_units
Revises: 0013_projects
Create Date: 2026-06-05

"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "0014_units"
down_revision: str | None = "0013_projects"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "units",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "business_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("businesses.id"),
            nullable=False,
        ),
        sa.Column(
            "project_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("projects.id"),
            nullable=False,
        ),
        sa.Column("label", sa.Text(), nullable=False),
        sa.Column(
            "buyer_counterparty_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("counterparties.id"),
            nullable=True,
        ),
        sa.Column("total_price_minor", sa.BigInteger(), nullable=True),
        sa.Column("currency", sa.String(), nullable=False, server_default="NGN"),
        sa.Column("status", sa.String(), nullable=False, server_default="available"),
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
    op.create_index("ix_units_business_id", "units", ["business_id"])
    op.create_index("ix_units_project_id", "units", ["project_id"])
    op.create_index("ix_units_buyer_counterparty_id", "units", ["buyer_counterparty_id"])


def downgrade() -> None:
    op.drop_index("ix_units_buyer_counterparty_id", table_name="units")
    op.drop_index("ix_units_project_id", table_name="units")
    op.drop_index("ix_units_business_id", table_name="units")
    op.drop_table("units")
