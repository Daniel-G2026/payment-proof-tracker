"""Phase 12 — backend observability: structured logging + optional Sentry.

Both are safe no-ops without configuration: logging always works (stdlib only),
and Sentry stays disabled until a DSN is provided, so dev / CI need no keys.
"""

from __future__ import annotations

import logging
import logging.config

from app.core.config import settings

log = logging.getLogger(__name__)


def configure_logging() -> None:
    """Configure stdlib logging once at startup via dictConfig.

    Lightweight single-line console format; level comes from config. We keep
    existing loggers enabled so uvicorn/arq loggers still emit.
    """
    logging.config.dictConfig(
        {
            "version": 1,
            "disable_existing_loggers": False,
            "formatters": {
                "standard": {
                    "format": "%(asctime)s %(levelname)s %(name)s %(message)s",
                },
            },
            "handlers": {
                "console": {
                    "class": "logging.StreamHandler",
                    "formatter": "standard",
                },
            },
            "root": {
                "handlers": ["console"],
                "level": settings.log_level.upper(),
            },
        }
    )


def init_sentry() -> bool:
    """Initialize Sentry when a DSN is configured. Returns True if enabled.

    No-op (returns False) when the DSN is empty, so debug / CI builds need no
    keys. Import is local so the SDK is only touched when actually used.
    """
    if not settings.sentry_dsn:
        return False
    import sentry_sdk

    sentry_sdk.init(
        dsn=settings.sentry_dsn,
        environment=settings.environment,
        traces_sample_rate=settings.sentry_traces_sample_rate,
    )
    log.info("Sentry initialized for environment=%s", settings.environment)
    return True
