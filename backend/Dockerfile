# Minimal image for running the Phase 8 arq worker. The API itself is still
# run locally via `uv run uvicorn` during development — this exists so
# `docker compose up` can boot the worker alongside Postgres and Redis
# without anyone having to remember a second terminal.
#
# Reusable for the API too if we ever want to containerize that side: same
# base, just swap the CMD.
FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_LINK_MODE=copy

# Install uv (matches the version we use locally — pin in CI later if needed).
RUN pip install --no-cache-dir uv==0.11.16

WORKDIR /app

# Install deps first so they get cached separately from source changes.
COPY pyproject.toml uv.lock* ./
RUN uv sync --frozen --no-dev || uv sync --no-dev

# Source code. In docker-compose dev we override this with a bind mount so
# edits hot-reload without a rebuild.
COPY app ./app
COPY alembic.ini ./
COPY alembic ./alembic

# Default to the worker. docker-compose can override.
CMD ["uv", "run", "arq", "app.worker.worker.WorkerSettings"]
