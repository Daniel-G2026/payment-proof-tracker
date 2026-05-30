# Shared image for both backend processes — the FastAPI API and the Phase 8
# arq worker. Same base and dependencies; the CMD selects which one runs:
#
#   - API (production):  gunicorn -k uvicorn.workers.UvicornWorker app.main:app
#   - Worker:            arq app.worker.worker.WorkerSettings  (default CMD)
#
# docker-compose wires both services off this single image (see the repo-root
# docker-compose.yml `api` and `worker` services).
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

# Default to the worker. The `api` compose service / production deploy
# overrides this with the gunicorn command above.
CMD ["uv", "run", "arq", "app.worker.worker.WorkerSettings"]
