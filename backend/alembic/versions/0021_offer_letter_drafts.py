"""add screenshots.document_type + schedule_drafts (Phase 13 Cut 6)

Revision ID: 0021_offer_letter_drafts
Revises: 0020_counterparty_types
Create Date: 2026-06-06

Offer-letter AI extraction. A `document_type` discriminator on screenshots lets
the existing extraction worker dispatch payment proofs vs. offer letters through
one pipeline; existing rows default to 'payment_proof' (no data migration). The
schedule_drafts table mirrors transaction_drafts — one AI draft per offer-letter
screenshot — with an `installments` JSONB array of cleaned schedule rows.

The partial index on created_at WHERE raw_json IS NOT NULL keeps the daily
purge job (purge_raw_json) cheap once most rows have been redacted.
"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "0021_offer_letter_drafts"
down_revision: str | None = "0020_counterparty_types"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "screenshots",
        sa.Column(
            "document_type",
            sa.String(),
            nullable=False,
            server_default="payment_proof",
        ),
    )
    op.create_table(
        "schedule_drafts",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "screenshot_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("screenshots.id", ondelete="CASCADE"),
            nullable=False,
            unique=True,
        ),
        sa.Column(
            "business_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("businesses.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("status", sa.String(), nullable=False),
        sa.Column("buyer_name", sa.String(), nullable=True),
        sa.Column("total_minor", sa.BigInteger(), nullable=True),
        sa.Column("currency_code", sa.String(length=8), nullable=True),
        sa.Column("installments", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("confidence", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("raw_json", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("error_code", sa.String(), nullable=True),
        sa.Column("error_message", sa.Text(), nullable=True),
        sa.Column("model_version", sa.String(), nullable=True),
        sa.Column("attempts", sa.Integer(), nullable=False, server_default="0"),
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
        sa.Column("confirmed_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index(
        "ix_schedule_drafts_business_id",
        "schedule_drafts",
        ["business_id"],
    )
    op.create_index(
        "ix_schedule_drafts_raw_json_purge",
        "schedule_drafts",
        ["created_at"],
        postgresql_where=sa.text("raw_json IS NOT NULL"),
    )


def downgrade() -> None:
    op.drop_index("ix_schedule_drafts_raw_json_purge", table_name="schedule_drafts")
    op.drop_index("ix_schedule_drafts_business_id", table_name="schedule_drafts")
    op.drop_table("schedule_drafts")
    op.drop_column("screenshots", "document_type")
