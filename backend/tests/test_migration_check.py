"""The startup migration guard: loud on a behind DB, silent when not managed."""

from __future__ import annotations

import pytest
from sqlalchemy import create_engine, text

from app.core.migration_check import verify_migrations_current


def _sqlite_engine():
    return create_engine("sqlite:///:memory:")


def test_skips_when_no_alembic_version_table():
    # A DB built from metadata.create_all has no alembic_version row → no raise.
    verify_migrations_current(_sqlite_engine())


def test_raises_when_db_behind_head():
    engine = _sqlite_engine()
    with engine.begin() as conn:
        conn.execute(text("CREATE TABLE alembic_version (version_num VARCHAR(32) NOT NULL)"))
        conn.execute(text("INSERT INTO alembic_version (version_num) VALUES ('0012_sync_events')"))
    with pytest.raises(RuntimeError, match="behind|expects"):
        verify_migrations_current(engine)


def test_passes_when_db_at_head():
    from alembic.config import Config
    from alembic.script import ScriptDirectory

    from app.core.migration_check import _DEFAULT_ALEMBIC_INI

    head = ScriptDirectory.from_config(Config(_DEFAULT_ALEMBIC_INI)).get_current_head()
    engine = _sqlite_engine()
    with engine.begin() as conn:
        conn.execute(text("CREATE TABLE alembic_version (version_num VARCHAR(64) NOT NULL)"))
        conn.execute(
            text("INSERT INTO alembic_version (version_num) VALUES (:v)"), {"v": head}
        )
    # At head → no raise.
    verify_migrations_current(engine)


def test_disabled_via_env(monkeypatch):
    monkeypatch.setenv("MIGRATION_CHECK_ENABLED", "false")
    engine = _sqlite_engine()
    with engine.begin() as conn:
        conn.execute(text("CREATE TABLE alembic_version (version_num VARCHAR(32) NOT NULL)"))
        conn.execute(
            text("INSERT INTO alembic_version (version_num) VALUES ('0001_initial_schema')")
        )
    # Disabled → returns without raising despite the mismatch.
    verify_migrations_current(engine)
