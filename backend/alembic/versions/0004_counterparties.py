"""add counterparties

Revision ID: 0004_counterparties
Revises: 0003_widen_amount_minor
Create Date: 2026-05-23

"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "0004_counterparties"
down_revision: str | None = "0003_widen_amount_minor"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "counterparties",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "business_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("businesses.id"),
            nullable=False,
        ),
        sa.Column("name", sa.Text(), nullable=False),
        sa.Column("normalized_name", sa.Text(), nullable=False),
        sa.Column("type", sa.String(), nullable=False),
        sa.Column("phone", sa.String(), nullable=True),
        sa.Column("notes", sa.Text(), nullable=True),
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
    op.create_index("ix_counterparties_business_id", "counterparties", ["business_id"])
    op.create_index(
        "ix_counterparties_business_id_normalized_name",
        "counterparties",
        ["business_id", "normalized_name"],
    )
    with op.batch_alter_table("transactions") as batch_op:
        batch_op.add_column(
            sa.Column(
                "counterparty_id",
                postgresql.UUID(as_uuid=True),
                sa.ForeignKey("counterparties.id"),
                nullable=True,
            )
        )
        batch_op.create_index("ix_transactions_counterparty_id", ["counterparty_id"])


def downgrade() -> None:
    with op.batch_alter_table("transactions") as batch_op:
        batch_op.drop_index("ix_transactions_counterparty_id")
        batch_op.drop_column("counterparty_id")
    op.drop_index("ix_counterparties_business_id_normalized_name", table_name="counterparties")
    op.drop_index("ix_counterparties_business_id", table_name="counterparties")
    op.drop_table("counterparties")
