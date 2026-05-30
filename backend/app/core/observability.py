"""Structured logging + error reporting (Phase 12).

Two responsibilities, intentionally tiny and dependency-light:

- `configure_logging()` installs a single stdout handler on the root logger.
  In production it emits one JSON object per line so any aggregator (Cloud
  Logging, Loki, Datadog, ...) can parse fields without a regex. In dev/test
  it falls back to a readable text format.
- `init_sentry()` wires up Sentry *only* when `SENTRY_DSN` is configured, so
  dev and test never phone home. The import is lazy: the app still boots if
  `sentry-sdk` isn't installed, it just logs a warning.

Both are called from the API (`app.main`) and the worker
(`app.worker.worker`) so backend errors and worker failures both surface.
"""

from __future__ import annotations

import json
import logging
from datetime import UTC, datetime

from app.core.config import settings

# Extra fields the request-logging middleware attaches; surfaced into the JSON
# payload when present.
_EXTRA_FIELDS = ("request_id", "method", "path", "status_code", "duration_ms")


class JsonLogFormatter(logging.Formatter):
    """Render each log record as a single-line JSON object."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, object] = {
            "ts": datetime.fromtimestamp(record.created, tz=UTC).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        for field in _EXTRA_FIELDS:
            value = getattr(record, field, None)
            if value is not None:
                payload[field] = value
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        return json.dumps(payload, default=str)


def configure_logging() -> None:
    """Install the chosen formatter on the root logger (idempotent)."""
    handler = logging.StreamHandler()
    if settings.log_json:
        handler.setFormatter(JsonLogFormatter())
    else:
        handler.setFormatter(
            logging.Formatter("%(asctime)s %(levelname)-8s %(name)s — %(message)s")
        )
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(settings.log_level.upper())


def init_sentry(component: str) -> bool:
    """Initialize Sentry if a DSN is configured. Returns True when active.

    `component` ("api" or "worker") is tagged on every event so the two
    processes are distinguishable in the Sentry UI.
    """
    if not settings.sentry_dsn:
        return False
    try:
        import sentry_sdk
    except ImportError:
        logging.getLogger(__name__).warning(
            "SENTRY_DSN is set but sentry-sdk is not installed; skipping error reporting."
        )
        return False

    sentry_sdk.init(
        dsn=settings.sentry_dsn,
        environment=settings.environment,
        traces_sample_rate=settings.sentry_traces_sample_rate,
        # Don't attach request bodies — proof uploads and tokens must never
        # leave the box inside an error report.
        send_default_pii=False,
    )
    sentry_sdk.set_tag("component", component)
    logging.getLogger(__name__).info("Sentry error reporting enabled for %s.", component)
    return True
