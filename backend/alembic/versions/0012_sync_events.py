"""add sync events

Revision ID: 0012_sync_events
Revises: 0011_categories
Create Date: 2026-06-05
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0012_sync_events"
down_revision: str | None = "0011_categories"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "sync_events",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("business_id", sa.Uuid(), nullable=False),
        sa.Column("actor_id", sa.Uuid(), nullable=False),
        sa.Column("entity_type", sa.String(length=64), nullable=False),
        sa.Column("entity_id", sa.String(length=64), nullable=True),
        sa.Column("action", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("error_message", sa.Text(), nullable=True),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "received_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.ForeignKeyConstraint(["actor_id"], ["profiles.id"]),
        sa.ForeignKeyConstraint(["business_id"], ["businesses.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_sync_events_actor_id", "sync_events", ["actor_id"])
    op.create_index("ix_sync_events_action", "sync_events", ["action"])
    op.create_index("ix_sync_events_business_id", "sync_events", ["business_id"])
    op.create_index("ix_sync_events_entity_id", "sync_events", ["entity_id"])
    op.create_index("ix_sync_events_entity_type", "sync_events", ["entity_type"])
    op.create_index("ix_sync_events_occurred_at", "sync_events", ["occurred_at"])
    op.create_index("ix_sync_events_status", "sync_events", ["status"])


def downgrade() -> None:
    op.drop_index("ix_sync_events_status", table_name="sync_events")
    op.drop_index("ix_sync_events_occurred_at", table_name="sync_events")
    op.drop_index("ix_sync_events_entity_type", table_name="sync_events")
    op.drop_index("ix_sync_events_entity_id", table_name="sync_events")
    op.drop_index("ix_sync_events_business_id", table_name="sync_events")
    op.drop_index("ix_sync_events_action", table_name="sync_events")
    op.drop_index("ix_sync_events_actor_id", table_name="sync_events")
    op.drop_table("sync_events")
