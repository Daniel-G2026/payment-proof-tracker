"""add screenshot image hash for duplicate detection

Revision ID: 0008_screenshot_image_hash
Revises: 0007_screenshots_orphan
Create Date: 2026-05-28
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0008_screenshot_image_hash"
down_revision: str | None = "0007_screenshots_orphan"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("screenshots", sa.Column("image_sha256", sa.String(length=64), nullable=True))
    op.create_index("ix_screenshots_image_sha256", "screenshots", ["image_sha256"])


def downgrade() -> None:
    op.drop_index("ix_screenshots_image_sha256", table_name="screenshots")
    op.drop_column("screenshots", "image_sha256")
