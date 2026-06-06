"""add schedule_versions table (Phase 13 Cut 2)

Revision ID: 0016_schedule_versions
Revises: 0015_transactions_project_unit
Create Date: 2026-06-05

"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "0016_schedule_versions"
down_revision: str | None = "0015_transactions_project_unit"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "schedule_versions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "business_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("businesses.id"),
            nullable=False,
        ),
        sa.Column(
            "unit_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("units.id"),
            nullable=False,
        ),
        sa.Column("version_no", sa.Integer(), nullable=False),
        sa.Column("source", sa.String(), nullable=False, server_default="manual"),
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column(
            "offer_letter_screenshot_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("screenshots.id"),
            nullable=True,
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
    op.create_index("ix_schedule_versions_business_id", "schedule_versions", ["business_id"])
    op.create_index("ix_schedule_versions_unit_id", "schedule_versions", ["unit_id"])
    # One active schedule per unit — enforced as a partial unique index.
    op.create_index(
        "uq_schedule_versions_unit_active",
        "schedule_versions",
        ["unit_id"],
        unique=True,
        postgresql_where=sa.text("is_active"),
    )


def downgrade() -> None:
    op.drop_index("uq_schedule_versions_unit_active", table_name="schedule_versions")
    op.drop_index("ix_schedule_versions_unit_id", table_name="schedule_versions")
    op.drop_index("ix_schedule_versions_business_id", table_name="schedule_versions")
    op.drop_table("schedule_versions")
