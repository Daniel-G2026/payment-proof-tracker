"""add earmarked_installment_id to transactions (Phase 13 Cut 3)

Revision ID: 0018_transactions_earmark
Revises: 0017_installments
Create Date: 2026-06-05

Additive, nullable, no backfill. The only stored allocation intent: a money-in
payment may point at a specific installment. null = pure waterfall. (unit_id and
project_id already landed in 0015.)
"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "0018_transactions_earmark"
down_revision: str | None = "0017_installments"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "transactions",
        sa.Column(
            "earmarked_installment_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("installments.id"),
            nullable=True,
        ),
    )


def downgrade() -> None:
    op.drop_column("transactions", "earmarked_installment_id")
