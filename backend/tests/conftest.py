"""Test-suite environment defaults.

pytest imports this before collecting any test module, so the env vars set
here land before `app.core.config.Settings()` is first instantiated (it runs
at import time of `app.main`). This makes the suite hermetic: no developer
`.env` and no live Postgres/Redis are required to run `pytest`, which is also
exactly what CI needs.

`setdefault` is used throughout so a developer who *does* want to point the
tests at a real database can still export the var explicitly.
"""

from __future__ import annotations

import os

# A lazily-created SQLAlchemy engine only needs a parseable URL at import time;
# every test overrides the DB dependency with its own in-memory SQLite engine.
os.environ.setdefault("DATABASE_URL", "sqlite://")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("ENVIRONMENT", "test")

# Keep the HTTP rate limiter out of the upload tests — they fire many uploads
# in a row and shouldn't pay a Redis round-trip (or a connect timeout when no
# Redis is running). The limiter itself is covered directly in
# test_phase12_hardening with fakeredis.
os.environ.setdefault("RATE_LIMIT_ENABLED", "false")

# Never emit JSON logs during tests; keeps pytest output readable.
os.environ.setdefault("LOG_JSON", "false")
