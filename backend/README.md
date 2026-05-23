# Backend

FastAPI service for Payment Proof Tracker.

## Setup

```powershell
cp .env.example .env
uv sync
uv run alembic upgrade head
uv run uvicorn app.main:app --reload
```

Requires Postgres reachable at `DATABASE_URL`. Use the repo-root `docker compose up -d` to start one locally.

## Commands

```powershell
uv run uvicorn app.main:app --reload     # dev server
uv run alembic upgrade head              # apply migrations
uv run alembic revision -m "msg" --autogenerate
uv run pytest
uv run ruff check .
uv run ruff format .
```

## Phase 1 scope

- `/health` endpoint
- Postgres connection
- Alembic configured
- Initial migration: `profiles`, `businesses`, `business_members`

Nothing else lands in Phase 1.
