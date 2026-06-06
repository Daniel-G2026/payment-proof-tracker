"""Fail-fast guard: refuse to start the API when the database schema is behind
the code's migrations.

This exists because a merged-but-unapplied migration is invisible to the test
suite (tests build the schema from ``metadata.create_all``, which always matches
the models) yet causes a 500 on the first real write against the live database.
Checking ``alembic_version`` vs the code's head at boot turns that silent,
confusing failure into a loud, actionable one.

Safe by construction:
- Skips cleanly when the DB has no ``alembic_version`` row (a fresh DB, or a
  test DB built from ``create_all``) — so it never breaks the unit suite.
- Skips (with a warning, no raise) if the DB is unreachable or the Alembic
  config can't be loaded — boot is not blocked on a transient.
- Disable entirely with ``MIGRATION_CHECK_ENABLED=false``.
"""

from __future__ import annotations

import logging
import os

from alembic.config import Config
from alembic.runtime.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy import Engine

logger = logging.getLogger(__name__)

# backend/app/core/migration_check.py -> backend/alembic.ini
_DEFAULT_ALEMBIC_INI = os.path.join(
    os.path.dirname(__file__), "..", "..", "alembic.ini"
)


def _disabled() -> bool:
    return os.getenv("MIGRATION_CHECK_ENABLED", "true").strip().lower() in (
        "0",
        "false",
        "no",
        "off",
    )


def verify_migrations_current(engine: Engine, alembic_ini: str = _DEFAULT_ALEMBIC_INI) -> None:
    if _disabled():
        return

    try:
        script = ScriptDirectory.from_config(Config(alembic_ini))
        head = script.get_current_head()
    except Exception as exc:  # noqa: BLE001 — config problems shouldn't hard-block boot
        logger.warning("Could not load Alembic head; skipping migration check: %s", exc)
        return

    try:
        with engine.connect() as conn:
            current = MigrationContext.configure(conn).get_current_revision()
    except Exception as exc:  # noqa: BLE001 — DB unreachable: don't block on a transient
        logger.warning("Could not read DB migration revision; skipping check: %s", exc)
        return

    if current is None:
        # No alembic_version row → DB isn't Alembic-managed (fresh / test). Skip.
        return

    if current != head:
        message = (
            f"Database is at migration '{current}' but this code expects '{head}'. "
            "Run `uv run alembic upgrade head` in backend/ before starting the API "
            "(or set MIGRATION_CHECK_ENABLED=false to bypass)."
        )
        logger.critical(message)
        raise RuntimeError(message)

    logger.info("Migration check OK: database at head '%s'.", head)
