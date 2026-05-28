"""add transaction_drafts + screenshots.ocr_text

Revision ID: 0006_transaction_drafts
Revises: 0005_screenshots
Create Date: 2026-05-25

Phase 8 (server-side OCR/AI extraction): new transaction_drafts table that
stores one extraction job per screenshot, plus an ocr_text column on
screenshots for any on-device OCR text the client uploads alongside the
image (used as a hint to the vision model later, not required).

The partial index on created_at WHERE raw_json IS NOT NULL keeps the daily
purge job (purge_raw_json) cheap once most rows have been redacted.
"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "0006_transaction_drafts"
down_revision: str | None = "0005_screenshots"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "screenshots",
        sa.Column("ocr_text", sa.Text(), nullable=True),
    )
    op.create_table(
        "transaction_drafts",
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
        sa.Column("amount_minor", sa.BigInteger(), nullable=True),
        sa.Column("currency_code", sa.String(length=8), nullable=True),
        sa.Column("transaction_date", sa.Date(), nullable=True),
        sa.Column("reference_number", sa.String(), nullable=True),
        sa.Column("bank_name", sa.String(), nullable=True),
        sa.Column("counterparty_name", sa.String(), nullable=True),
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
        "ix_transaction_drafts_business_id",
        "transaction_drafts",
        ["business_id"],
    )
    # Partial index so the daily purge job only scans rows that still hold a
    # raw_json blob, not the (eventually much larger) set of redacted rows.
    op.create_index(
        "ix_transaction_drafts_raw_json_purge",
        "transaction_drafts",
        ["created_at"],
        postgresql_where=sa.text("raw_json IS NOT NULL"),
    )


def downgrade() -> None:
    op.drop_index("ix_transaction_drafts_raw_json_purge", table_name="transaction_drafts")
    op.drop_index("ix_transaction_drafts_business_id", table_name="transaction_drafts")
    op.drop_table("transaction_drafts")
    op.drop_column("screenshots", "ocr_text")
