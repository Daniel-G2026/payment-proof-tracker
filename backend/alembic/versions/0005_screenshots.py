"""add screenshots

Revision ID: 0005_screenshots
Revises: 0004_counterparties
Create Date: 2026-05-24

"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "0005_screenshots"
down_revision: str | None = "0004_counterparties"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "screenshots",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "business_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("businesses.id"),
            nullable=False,
        ),
        sa.Column(
            "transaction_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("transactions.id"),
            nullable=False,
        ),
        sa.Column(
            "uploaded_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("profiles.id"),
            nullable=False,
        ),
        sa.Column("mime_type", sa.String(), nullable=False),
        sa.Column("uploaded_file_size", sa.BigInteger(), nullable=False),
        sa.Column("original_file_size", sa.BigInteger(), nullable=False),
        sa.Column("width", sa.Integer(), nullable=True),
        sa.Column("height", sa.Integer(), nullable=True),
        sa.Column("storage_key", sa.String(), nullable=False, unique=True),
        sa.Column("storage_status", sa.String(), nullable=False, server_default="stored"),
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
    op.create_index("ix_screenshots_business_id", "screenshots", ["business_id"])
    op.create_index("ix_screenshots_transaction_id", "screenshots", ["transaction_id"])
    op.create_index("ix_screenshots_uploaded_by", "screenshots", ["uploaded_by"])


def downgrade() -> None:
    op.drop_index("ix_screenshots_uploaded_by", table_name="screenshots")
    op.drop_index("ix_screenshots_transaction_id", table_name="screenshots")
    op.drop_index("ix_screenshots_business_id", table_name="screenshots")
    op.drop_table("screenshots")
