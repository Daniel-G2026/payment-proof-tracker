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

## Current scope

The backend has moved beyond Phase 1. It now supports:

- health, auth-backed profile, business, people, and transaction routes
- local-first idempotent creates from Android-generated UUIDs
- screenshot proof upload, signed content access, and orphan proof linking
- server-side extraction drafts for uploaded proofs
- duplicate-candidate checks for warning-only duplicate prevention
- dependency-aware offline sync support, conflict checks, and audit events
- proof restore endpoints and online PDF report export

Current active phase: Phase 12 polish, analytics, and reliability.
