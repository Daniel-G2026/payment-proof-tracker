# Backend production deployment guide

The mobile app talks to a backend that is **two long-running processes plus
three backing services**. Getting all five right is the whole job.

## What has to run

| Piece | Command | Notes |
|---|---|---|
| **API** | `uv run uvicorn app.main:app --host 0.0.0.0 --port $PORT` | Stateless HTTP. Scale horizontally if needed. |
| **Worker** | `uv run arq app.worker.worker.WorkerSettings` | **Must be always-on.** Runs AI extraction jobs **and** the daily crons (`purge_raw_json`, `purge_orphan_screenshots`). These crons live *inside* the worker — there is no separate cron service to schedule. |
| **Postgres** | — | `DATABASE_URL` (async psycopg). Needs backups. |
| **Redis** | — | `REDIS_URL`. Backs the arq queue + the extraction rate-limit token bucket. |
| **Object storage (S3)** | — | Proof images. **Do not use local disk in prod** (`PROOF_STORAGE_BACKEND=local` is ephemeral and dies on redeploy). |

Both processes use the **same image** (`backend/Dockerfile`) — it defaults to the
worker `CMD`; override the start command for the API service. Run
**`uv run alembic upgrade head`** against the prod DB as a release/predeploy step
before the new code serves traffic.

External services you also depend on: **Supabase** (auth — verifies the JWT the
app sends), **Anthropic** (Claude extraction), and optionally **Sentry**/**PostHog**.

## Hosting options compared

All four can run this stack. Ordered by how little ops you'll do.

### 1. Render — *recommended starting point*
- **API** → Web Service (Docker). **Worker** → Background Worker (same repo/Dockerfile, custom start command). **Redis** + **Postgres** → Render's managed add-ons.
- Predeploy hook runs `alembic upgrade head`. Env vars in the dashboard / `render.yaml`.
- **Pros:** background workers and managed Redis/Postgres are first-class; `render.yaml` checks the whole topology into the repo; generous enough free/cheap tiers to start. **Cons:** can get pricey as you scale; cold starts on free tier.
- **Why first:** it maps 1:1 onto "API + always-on worker + Redis + Postgres" with the least glue.

### 2. Railway
- Similar model: a service per process, plugin Postgres + Redis. Very fast to stand up.
- **Pros:** great DX, simple pricing, easy multi-service. **Cons:** usage-based billing can surprise you; fewer compliance knobs.

### 3. Fly.io
- Run the image as two process groups (`[processes]` in `fly.toml`), Fly Postgres, Upstash Redis.
- **Pros:** cheap, runs close to users (good if your users are all in Nigeria — deploy to a nearby region), scales to zero. **Cons:** more hands-on (volumes, networking, Postgres you partly manage); steeper learning curve.

### 4. VPS + Docker Compose (DIY)
- One box, your existing `docker-compose.yml` adapted for prod: add an `api` service, swap dev passwords for secrets, drop the source bind-mounts, put **nginx/Caddy + TLS** in front, switch proof storage to S3.
- **Pros:** cheapest at scale, full control. **Cons:** *you* are now on-call for OS patching, TLS renewal, backups, restarts, monitoring. Most ops burden of the four.

**Recommendation:** start on **Render** (or Railway if you prefer the DX). Move to Fly/VPS only if cost or latency forces it. Whatever you pick, the non-negotiables below are the same.

## Pre-launch checklist (host-agnostic)

- [ ] API and worker both deployed from `backend/Dockerfile`; worker start command set to the arq command.
- [ ] `alembic upgrade head` runs as a predeploy/release step against prod Postgres.
- [ ] Managed **Postgres** with automated backups enabled.
- [ ] Managed **Redis** reachable by both processes.
- [ ] `PROOF_STORAGE_BACKEND=s3` + all `PROOF_STORAGE_S3_*` set; `PROOF_SIGNED_URL_SECRET` is a strong random value. Verify upload → signed-URL fetch → **fresh-device proof restore** against the real bucket.
- [ ] **Supabase** prod project: `SUPABASE_URL`, `SUPABASE_JWT_SECRET`, `SUPABASE_ANON_KEY`. Confirm prod is separate from any dev project.
- [ ] `ANTHROPIC_API_KEY` set; **billing cap / usage alert** configured on the Anthropic account; `EXTRACTION_ENABLED=true`, model + rate limits reviewed.
- [ ] `ENVIRONMENT=production`; CORS / allowed origins reviewed.
- [ ] **Sentry** (backend + mobile) and **PostHog** keys set so they're not no-op in prod.
- [ ] App's `API_BASE_URL` (in `android/local.properties` → release build) points at the prod API over **HTTPS**.
- [ ] Confirm the worker's crons actually fire in prod (check logs the next day) — see `docs/backups-and-retention.md`.

## Env var reference

Source of truth is `backend/.env.example`. Production-critical groups:
`DATABASE_URL`, `REDIS_URL`, `ENVIRONMENT`; `SUPABASE_*`; `PROOF_STORAGE_*` +
`PROOF_SIGNED_URL_SECRET`; `ANTHROPIC_API_KEY` + `EXTRACTION_*`. Observability:
`SENTRY_DSN`, `POSTHOG_API_KEY` (mobile side lives in `android/local.properties`).
