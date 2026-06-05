"""Phase 12 Cut 1 — observability (backend) tests.

Sentry init is verified without contacting Sentry: the no-op path needs no
keys, and the enabled path patches ``sentry_sdk.init`` so nothing is sent.
"""

from __future__ import annotations

import logging

from app.core import observability
from app.core.config import settings


def test_init_sentry_noop_without_dsn():
    original = settings.sentry_dsn
    settings.sentry_dsn = ""
    try:
        assert observability.init_sentry() is False
    finally:
        settings.sentry_dsn = original


def test_init_sentry_enabled_with_dsn(monkeypatch):
    captured: dict[str, object] = {}

    def fake_init(**kwargs):
        captured.update(kwargs)

    import sentry_sdk

    monkeypatch.setattr(sentry_sdk, "init", fake_init)
    original = settings.sentry_dsn
    settings.sentry_dsn = "https://public@example.com/1"
    try:
        assert observability.init_sentry() is True
    finally:
        settings.sentry_dsn = original

    assert captured["dsn"] == "https://public@example.com/1"
    assert captured["environment"] == settings.environment


def test_configure_logging_sets_root_level():
    original = settings.log_level
    settings.log_level = "WARNING"
    try:
        observability.configure_logging()
        assert logging.getLogger().level == logging.WARNING
    finally:
        settings.log_level = original
        observability.configure_logging()  # restore default level
