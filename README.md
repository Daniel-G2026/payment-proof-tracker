# Payment Proof Tracker

Android-native, offline-first payment proof tracker for small businesses.

The app helps small businesses organize payment screenshots into a searchable business ledger.

## Core Promise

Upload or share payment screenshots, review the AI-drafted details, and keep a clean record of who paid, who was paid, and what money moved.

## Initial Target Market

Small Nigerian construction-adjacent businesses, suppliers, subcontractors, and family-run businesses that currently track payments through WhatsApp screenshots, notebooks, spreadsheets, and memory.

## Tech Direction

- Android: Kotlin + Jetpack Compose
- Local storage: Room SQLite
- Background sync: WorkManager
- AI extraction: server-side Claude vision drafts, with local OCR code gated off for dogfooding
- Backend: FastAPI
- Database: Postgres
- Storage: S3-compatible or Supabase Storage

## Current Progress

- Phases 1-6 are implemented: foundation, auth/business setup, manual records, counterparties, screenshot proof attachment, and Android share-sheet intake.
- Phase 8 and 8.1 are implemented: server-side AI extraction creates editable drafts, and new proofs eagerly upload on attach so drafts can pre-fill during record creation.
- Phase 9 is implemented: duplicate detection is warning-only, checks local data first, falls back safely if the backend is unavailable, and compares proof hashes, references, and amount/date/counterparty signals.
- Recent polish: amount entry now uses comma-separated numbers, and a small responsiveness audit moved proof file work off the UI thread.
- Current phase: Phase 10 offline sync hardening. The first slice makes background sync drain every locally synced business, preserves dependency order, and cleans up stale Phase 1-only status docs.

## Fresh-Machine Setup

Tested commands for going from a clean `git clone` to a running stack. See
`docs/mobile-development-things-to-know.md` for deeper notes and
troubleshooting; this section is the minimal happy path.

### Prerequisites

- Git
- Docker Desktop (running before the backend commands below)
- Python 3.13 + [uv](https://docs.astral.sh/uv/)
- JDK 17 (Android Gradle Plugin 8.x requirement)
- Android Studio (Iguana or newer) or the standalone Android command-line
  tools + an emulator image (API 34+ recommended)

### Backend

```powershell
git clone <repo-url> payment-proof-tracker
cd payment-proof-tracker
Copy-Item backend\.env.example backend\.env   # then edit secrets as needed
docker compose up -d                          # Postgres + Redis
cd backend
uv sync                                       # install Python deps
uv run alembic upgrade head                   # apply migrations
uv run pytest                                 # verify suite passes
uv run uvicorn app.main:app --reload          # start the API on :8000
```

Verify in a second shell:

```powershell
Invoke-WebRequest http://localhost:8000/health -UseBasicParsing
```

### Android

```powershell
cd android
Copy-Item local.properties.example local.properties   # if the file exists
# Otherwise create android\local.properties with at minimum:
#   sdk.dir=<path-to-your-Android-SDK>
#   API_BASE_URL=http://10.0.2.2:8000/
.\gradlew.bat :app:testDebugUnitTest                  # verify unit tests pass
.\gradlew.bat :app:assembleDebug                      # build the APK
```

The emulator must reach the backend at `10.0.2.2:8000` (not `localhost`).
For a physical device on the same Wi-Fi, point `API_BASE_URL` at your
machine's LAN IP and ensure the firewall allows port 8000.

### What to run after each pull

```powershell
docker compose up -d                          # restart containers if stopped
cd backend && uv sync && uv run alembic upgrade head
cd android && .\gradlew.bat :app:testDebugUnitTest
```

## Docs

See `docs/master-plan.md` for the full product and architecture plan.
See `docs/mobile-development-things-to-know.md` for local mobile/backend development notes.
