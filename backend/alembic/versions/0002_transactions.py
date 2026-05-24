"""add manual transactions

Revision ID: 0002_transactions
Revises: 0001_initial_schema
Create Date: 2026-05-23

"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "0002_transactions"
down_revision: str | None = "0001_initial_schema"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "transactions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "business_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("businesses.id"),
            nullable=False,
        ),
        sa.Column("amount_minor", sa.Integer(), nullable=False),
        sa.Column("currency", sa.String(), nullable=False, server_default="NGN"),
        sa.Column("direction", sa.String(), nullable=False),
        sa.Column("transaction_date", sa.Date(), nullable=False),
        sa.Column("counterparty_name", sa.String(), nullable=True),
        sa.Column("category", sa.String(), nullable=True),
        sa.Column("note", sa.String(), nullable=True),
        sa.Column("bank_name", sa.String(), nullable=True),
        sa.Column("reference_number", sa.String(), nullable=True),
        sa.Column("source", sa.String(), nullable=False, server_default="manual"),
        sa.Column("status", sa.String(), nullable=False, server_default="confirmed"),
        sa.Column(
            "created_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("profiles.id"),
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
    op.create_index("ix_transactions_business_id", "transactions", ["business_id"])
    op.create_index("ix_transactions_direction", "transactions", ["direction"])
    op.create_index("ix_transactions_transaction_date", "transactions", ["transaction_date"])


def downgrade() -> None:
    op.drop_index("ix_transactions_transaction_date", table_name="transactions")
    op.drop_index("ix_transactions_direction", table_name="transactions")
    op.drop_index("ix_transactions_business_id", table_name="transactions")
    op.drop_table("transactions")
