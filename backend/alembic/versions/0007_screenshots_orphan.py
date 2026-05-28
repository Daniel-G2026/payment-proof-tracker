"""screenshots.transaction_id nullable for orphan uploads

Revision ID: 0007_screenshots_orphan
Revises: 0006_transaction_drafts
Create Date: 2026-05-25

Phase 8.1 (eager upload on attach): a screenshot can now exist without a
parent transaction. The Android form uploads the proof the moment the user
commits to it (picker tap or share-sheet Money In/Out tap) so extraction
can run in parallel while the user is still on the form. On save, the
transaction-create endpoint links the orphan in the same DB transaction.

Two related changes:
  - drop NOT NULL on transaction_id
  - switch the FK to ON DELETE SET NULL so deleting a transaction doesn't
    cascade-delete a proof the user still wants in their gallery
  - partial index for the orphan-cleanup cron, which scans only rows where
    transaction_id IS NULL (eventually the dominant non-orphan case)

Downgrade has to delete orphans first since re-adding NOT NULL on a column
with NULLs would fail. The downgrade also restores the original FK
(no ON DELETE clause = RESTRICT).
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0007_screenshots_orphan"
down_revision: str | None = "0006_transaction_drafts"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


# Alembic auto-generates an FK name when one isn't specified; the original
# migration (0005) didn't name it, so Postgres assigned the default of
# "screenshots_transaction_id_fkey". Centralized here so the up/down stays
# symmetric.
FK_NAME = "screenshots_transaction_id_fkey"


def upgrade() -> None:
    op.alter_column(
        "screenshots",
        "transaction_id",
        existing_type=sa.dialects.postgresql.UUID(as_uuid=True),
        nullable=True,
    )
    op.drop_constraint(FK_NAME, "screenshots", type_="foreignkey")
    op.create_foreign_key(
        FK_NAME,
        "screenshots",
        "transactions",
        ["transaction_id"],
        ["id"],
        ondelete="SET NULL",
    )
    # Partial index — the cleanup cron scans only orphans, and as the table
    # grows the fraction of rows where transaction_id IS NULL will be small
    # (only un-saved attaches). Keeps the scan cheap forever.
    op.create_index(
        "ix_screenshots_orphan_created_at",
        "screenshots",
        ["created_at"],
        postgresql_where=sa.text("transaction_id IS NULL"),
    )


def downgrade() -> None:
    op.drop_index("ix_screenshots_orphan_created_at", table_name="screenshots")
    # Re-adding NOT NULL would fail on orphan rows. Soft-delete them first
    # (matches the cron's semantics) so we can downgrade safely on prod data.
    op.execute(
        "UPDATE screenshots SET deleted_at = now() "
        "WHERE transaction_id IS NULL AND deleted_at IS NULL"
    )
    op.execute("DELETE FROM screenshots WHERE transaction_id IS NULL")
    op.drop_constraint(FK_NAME, "screenshots", type_="foreignkey")
    op.create_foreign_key(
        FK_NAME,
        "screenshots",
        "transactions",
        ["transaction_id"],
        ["id"],
    )
    op.alter_column(
        "screenshots",
        "transaction_id",
        existing_type=sa.dialects.postgresql.UUID(as_uuid=True),
        nullable=False,
    )
