"""widen transactions.amount_minor to bigint

Phase 3 shipped with Integer (int4, ~₦21.4M ceiling in kobo). Real construction
SME payments can exceed that easily, so widen to BigInteger before any
production data exists.

Revision ID: 0003_widen_amount_minor
Revises: 0002_transactions
Create Date: 2026-05-23

"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0003_widen_amount_minor"
down_revision: str | None = "0002_transactions"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    with op.batch_alter_table("transactions") as batch_op:
        batch_op.alter_column(
            "amount_minor",
            existing_type=sa.Integer(),
            type_=sa.BigInteger(),
            existing_nullable=False,
        )


def downgrade() -> None:
    with op.batch_alter_table("transactions") as batch_op:
        batch_op.alter_column(
            "amount_minor",
            existing_type=sa.BigInteger(),
            type_=sa.Integer(),
            existing_nullable=False,
        )
