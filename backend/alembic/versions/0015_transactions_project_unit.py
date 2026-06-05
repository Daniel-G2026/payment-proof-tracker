"""add nullable project_id/unit_id to transactions (Phase 13 Cut 1)

Revision ID: 0015_transactions_project_unit
Revises: 0014_units
Create Date: 2026-06-05

Additive, nullable, no backfill. Money-in carries unit_id; money-out carries
project_id. Columns land here in Cut 1 so the schema settles once; the
create/edit flow is wired in Cut 3 (unit) and Cut 5 (project).
"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "0015_transactions_project_unit"
down_revision: str | None = "0014_units"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "transactions",
        sa.Column(
            "project_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("projects.id"),
            nullable=True,
        ),
    )
    op.add_column(
        "transactions",
        sa.Column(
            "unit_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("units.id"),
            nullable=True,
        ),
    )
    op.create_index("ix_transactions_project_id", "transactions", ["project_id"])
    op.create_index("ix_transactions_unit_id", "transactions", ["unit_id"])


def downgrade() -> None:
    op.drop_index("ix_transactions_unit_id", table_name="transactions")
    op.drop_index("ix_transactions_project_id", table_name="transactions")
    op.drop_column("transactions", "unit_id")
    op.drop_column("transactions", "project_id")
