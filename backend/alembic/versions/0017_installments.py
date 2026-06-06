"""add installments table (Phase 13 Cut 2)

Revision ID: 0017_installments
Revises: 0016_schedule_versions
Create Date: 2026-06-05

"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "0017_installments"
down_revision: str | None = "0016_schedule_versions"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "installments",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "business_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("businesses.id"),
            nullable=False,
        ),
        sa.Column(
            "schedule_version_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("schedule_versions.id"),
            nullable=False,
        ),
        sa.Column("sequence", sa.Integer(), nullable=False),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("due_date", sa.Date(), nullable=False),
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
    op.create_index("ix_installments_business_id", "installments", ["business_id"])
    op.create_index(
        "ix_installments_schedule_version_id", "installments", ["schedule_version_id"]
    )


def downgrade() -> None:
    op.drop_index("ix_installments_schedule_version_id", table_name="installments")
    op.drop_index("ix_installments_business_id", table_name="installments")
    op.drop_table("installments")
