# v1 Release Checklist

**Status as of 2026-06-07:** the *product* is feature-complete — Phases 1–13 are
built and merged (manual ledger, counterparties, proof capture + restore,
share-sheet intake, AI drafting, duplicate detection, offline sync hardening,
reports/export, analytics/observability, edit history, and the full Phase 13
Projects / offer-letter / installment-arrears money-in reframe). The nav
redesign (PR #37) is the last open code change.

This document is the gap between **"code complete"** and **"in a user's hands."**
None of it is new feature work — it's release engineering, deployment, and
compliance. Items are ordered roughly by what blocks a release.

---

## 0. Merge what's open

- [ ] Merge **PR #37** (`phase13-nav-projects-tab`) into `main` and delete the branch.
- [ ] Confirm `main` is green: backend `uv run pytest`, `:app:testDebugUnitTest`,
      `:app:assembleDebug`.

---

## 1. Mobile release build — **BLOCKER**

Everything has only ever been verified with `assembleDebug`. A shippable build
is different in two ways that bite late:

- [ ] **Real signing key.** `android/app/build.gradle.kts` currently signs the
      `release` build type with the **debug keystore** (there's an explicit
      `TODO(release)` at the `signingConfigs` block). Google Play will reject a
      debug-signed artifact. Generate a real upload keystore, load it from
      `local.properties`/CI secrets (never commit it), and back it up somewhere
      you will not lose — losing it means you can never update the app under the
      same listing (unless enrolled in Play App Signing).
- [ ] **Cut and test a release build.** `release` has `isMinifyEnabled = true`
      and `isShrinkResources = true` (R8). A minified build can crash where debug
      didn't — reflection-based libs (Moshi, Retrofit, Room, Hilt) need keep
      rules. `proguard-rules.pro` exists (77 lines) but has never been exercised
      end-to-end. Build `:app:assembleRelease` (or `:app:bundleRelease` for an
      AAB) and **smoke-test the actual minified artifact on a device** — sign in,
      add a record, attach proof, sync, open Projects/arrears, export.
- [ ] **Bump version.** Currently `versionCode = 1`, `versionName = "0.2.0"`.
      Decide the public v1 version (e.g. `1.0.0` / `versionCode 1`) and set it.
- [ ] **Produce an AAB** (`bundleRelease`) — Play requires App Bundles, not APKs,
      for new apps.

---

## 2. Backend production deploy — **BLOCKER**

The app talks to a FastAPI backend (+ arq worker on Redis). Today that's a local
`docker-compose` stack (postgres, redis, worker). Production needs real,
always-on infrastructure. Env keys live in `backend/.env.example`.

- [ ] **Hosting** for the FastAPI API **and** the separate arq **worker** process
      (the worker runs AI extraction + the daily crons: `purge_raw_json`,
      `purge_orphan_screenshots`). Both must be running; the worker is easy to
      forget.
- [ ] **Managed Postgres** (`DATABASE_URL`) with backups. Run migrations against it.
- [ ] **Managed Redis** (`REDIS_URL`) — backs the arq queue and the extraction
      rate-limit token bucket.
- [ ] **Supabase project** (auth): `SUPABASE_URL`, `SUPABASE_JWT_SECRET`,
      `SUPABASE_ANON_KEY` set to the production project. A Supabase project is
      already connected via MCP — confirm prod vs. dev separation.
- [ ] **Proof storage** for production: set `PROOF_STORAGE_BACKEND` to S3 (not
      `local` — local disk won't survive redeploys) and fill the
      `PROOF_STORAGE_S3_*` keys + `PROOF_SIGNED_URL_SECRET`. Verify upload,
      signed-url fetch, and fresh-device proof restore against the real bucket.
- [ ] **Anthropic key** (`ANTHROPIC_API_KEY`) + extraction config
      (`EXTRACTION_ENABLED`, `EXTRACTION_MODEL`, rate limits, retention). Set a
      billing cap/alert on the Anthropic account.
- [ ] **Observability keys** (currently no-op without them): backend Sentry,
      mobile Sentry, PostHog. Turn these on for prod so you can see crashes and
      funnels from day one.
- [ ] **CORS / allowed origins** and `ENVIRONMENT=production` reviewed.
- [ ] **Point the app at prod.** Confirm the Android base URL is configurable per
      build type and the release build targets the production API.

---

## 3. Store listing & compliance — **BLOCKER for public launch**

- [ ] **Privacy policy** (hosted URL). Non-negotiable: the app captures payment
      screenshots and personal counterparty data. Required by Google Play and by
      law in most regions.
- [ ] **Play Data Safety form** — declare what's collected (images, financial
      records, account/email), how it's stored, and that it's encrypted in
      transit. Must match what the app actually does.
- [ ] **Play Console setup**: developer account, app listing, screenshots,
      feature graphic, short/full description in the product's plain language
      ("money in, money out, proof"), content rating, target audience.
- [ ] **Permissions audit** — justify each permission requested (camera/photos,
      internet). Remove any unused ones.
- [ ] **Account/data deletion path** — Play now requires a way for users to
      request account + data deletion. Confirm there's a route (in-app or a
      documented request flow) and that it deletes proof bytes + records.

---

## 4. Real-device QA (not just the emulator)

All verification so far has been on a Pixel 7 emulator. `docs/performance-notes.md`
already flags cold-start / low-end profiling as deferred.

- [ ] Test on a genuine **low-end Android device** (low RAM, slow storage, older
      Android near `minSdk 24`) — cold start, scrolling the Projects/arrears
      feeds, large proof image compression.
- [ ] **Offline / flaky-network** pass: airplane-mode saves, queued sync drain,
      session-expiry handling, proof restore on a fresh sign-in.
- [ ] **End-to-end happy path** on the release (minified) build: sign up → create
      business → add money in/out → attach proof → AI draft → confirm → project +
      installment schedule → arrears → reports/export.
- [ ] Verify **share-sheet intake** from a real gallery/WhatsApp share.

---

## 5. Known loose ends (documented — mostly fine to ship)

These are already recorded in `CLAUDE.md` / `master-plan.md`; listed here so the
launch decision is deliberate, not accidental.

- [ ] **On-device OCR is gated off** (`OCR_DRAFTING_ENABLED = false`) — intended;
      Phase 8's vision model handles drafting. Leave off for v1.
- [ ] **3 unwired PostHog events** (`screenshot_uploaded`, `draft_confirmed`,
      `export_created`) — wire if you want those funnels at launch, else defer.
- [ ] **Eager-upload + offline-save edge case** (Phase 8.1) — proof link can be
      lost if attached online then saved offline within seconds; self-heals via
      the 7-day orphan cron + re-attach. Document, don't fix.
- [ ] Broader reusable loading/empty/error composable adoption — cosmetic, defer.

---

## 6. Post-launch readiness

- [ ] Confirm the **daily crons actually run** in prod (raw-json purge, orphan
      screenshot purge) — see `docs/backups-and-retention.md`.
- [ ] **DB backup/restore** rehearsed at least once.
- [ ] A way to **roll back** a bad release (staged Play rollout %, previous AAB
      kept).
- [ ] Decide a **support channel** for the first users and a place to triage
      Sentry crashes.

---

### TL;DR

The build is done. To actually ship v1 the critical path is: **merge #37 → real
keystore + tested minified release AAB → production backend (API + worker +
Postgres + Redis + S3 + Supabase + Anthropic) → privacy policy + Play Data
Safety → low-end-device QA.** Everything else is polish or already-accepted
trade-offs.
