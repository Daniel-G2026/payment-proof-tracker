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

## Health checks

- `GET /health` — liveness. Cheap, dependency-free; never restart a pod over a
  transient dependency blip based on this.
- `GET /health/ready` — readiness. Verifies Postgres connectivity (returns
  `503` if down so a load balancer stops routing to the pod) and reports Redis
  status (non-fatal — the app fails open without it).

## Observability

- **Structured logging.** `LOG_JSON=true` emits one JSON object per request/log
  line for aggregators; every response carries an `X-Request-ID` (preserved
  from the caller when supplied) for tracing across the client, proxy, and API.
- **Error reporting.** Set `SENTRY_DSN` to enable Sentry on both the API and
  the worker (tagged `component=api` / `component=worker`). Unset in dev/test,
  so neither phones home.

## Rate limiting & hardening

- Per-user upload rate limiting (`RATE_LIMIT_UPLOAD_PER_MINUTE`, Redis-backed,
  fails open if Redis is unreachable). Closes the "authenticated client loops
  POSTs to fill object storage" gap.
- Security headers (`X-Content-Type-Options: nosniff`, `X-Frame-Options`,
  `Referrer-Policy`, plus HSTS outside development) on every response.
- CORS stays **off** until `CORS_ALLOW_ORIGINS` is set — list the web-dashboard
  origin(s) explicitly when it ships; never `*`.

## Deployment

The `backend/Dockerfile` builds one image used by both processes. Production
runs the API under gunicorn with uvicorn workers, and the worker under arq:

```bash
# API (production process manager)
uv run gunicorn app.main:app \
  --worker-class uvicorn.workers.UvicornWorker --workers 2 --bind 0.0.0.0:8000

# Extraction worker
uv run arq app.worker.worker.WorkerSettings
```

Run migrations before serving: `uv run alembic upgrade head`.

The repo-root `docker compose up` wires the full stack — Postgres, Redis, a
one-shot `migrate` job (the `api`/`worker` wait for it to finish), the gunicorn
`api` on `:8000`, and the `worker`.

Deployment-time operational hygiene (see `master-plan.md` §13.2): configure the
reverse proxy / CDN to scrub `signature`/`expires` query params from proof URLs
out of access logs, and set a slow-body (`client_body_timeout`) limit there to
cover Slowloris on the streaming upload route.

## Current scope

The backend has moved beyond Phase 1. It now supports:

- health/readiness probes, structured logging, Sentry, and per-user rate limits
- auth-backed profile, business, people, and transaction routes
- local-first idempotent creates from Android-generated UUIDs
- screenshot proof upload, signed content access, and orphan proof linking
- server-side extraction drafts for uploaded proofs
- duplicate-candidate checks for warning-only duplicate prevention

Current active phase: Phase 12 reliability hardening (CI, observability, deploy).
