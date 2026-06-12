# CLAUDE.md

## Product
This is an Android-native, offline-first payment proof tracker for small businesses. It is not full accounting software in v1.

## Non-Negotiables
- Android-native first using Kotlin + Jetpack Compose.
- Offline-first local save using Room.
- Payment screenshots are preserved as proof.
- OCR/AI creates drafts only.
- User confirmation is required before final transaction creation.
- Minimal required fields.
- No full accounting, tax, payroll, bank integration, invoice system, or WhatsApp bot in v1.
- Use simple user-facing language: money in, money out, proof, who paid, who you paid.

## Build Discipline
- Build one phase at a time.
- Do not implement future-phase features early.
- Before coding, inspect the repo and propose files to change.
- After coding, run the relevant build/tests.
- Keep architecture clean and modular.

## Git Workflow
- Use a branch-and-PR flow; do not commit directly to `main`.
- Each phase (or cut of a phase) gets its own feature branch off `main`.
- Keep commits logically scoped with descriptive messages (what + why).
- Open a PR into `main`; merge after review/CI rather than pushing to `main`.
- Only commit/push when explicitly asked.

## Architecture Boundaries
- UI polish and redesign are expected later, including settings, multiple businesses, tabs, and navigation changes.
- Keep infrastructure explicitly separate from UI so redesigns do not require storage/network rewrites.
- Compose screens should not call Room DAOs, Retrofit/API services, WorkManager, file storage, or token storage directly.
- Screens should read ViewModel state and send simple user events.
- ViewModels may coordinate screen behavior, validation, and repository calls, but heavy data, sync, backup, auth, and file logic belongs in repositories/services.
- Infrastructure classes must not depend on Compose or screen layout.
- If UI state becomes complex, add dedicated UI state/models instead of bending database entities around presentation needs.

## Current Phase

Phase 12 (Polish, Analytics, and Reliability) is **complete** (merged to `main`):
security/validation hardening (upload rate-limiting, currency ISO-4217
allowlist, search LIKE escaping, CORS); observability (backend Sentry +
structured logging, mobile Sentry, PostHog analytics — all no-op without keys);
a user-visible edit history over the `audit_events` diffs; the UI/navigation
polish pass (Reports promoted to a top-level tab; "Manage categories" moved into
the Records header; reusable loading/empty/error composables; a first-run
onboarding tip); and the server-side `sync_events` table with on-device flush.
Verified on `main` 2026-06-05: backend `uv run pytest` (147 passed),
`:app:testDebugUnitTest`, and `:app:assembleDebug`. **Update (2026-06-11):
observability is now ON for the v1 launch** — the three previously-deferred
PostHog events are wired (`screenshot_uploaded` fires on a successful eager
proof upload and `draft_confirmed` on saving an AI-draft-backed transaction,
both in `TransactionFormViewModel`; `export_created` alongside the existing
`SyncEventLogger` log in `SummaryViewModel`), so Sentry (crash reporting) +
PostHog (product analytics) are both enabled (they stay no-op only in builds
without keys; keys live in `local.properties`). Privacy policy +
Play Data Safety draft updated to reflect collected diagnostics/analytics.
Remaining deferred follow-ups: broader reusable-state-composable adoption, and
on-device cold-start / low-end-device profiling (see
`docs/performance-notes.md`).

The next major direction is **Phase 13: Projects, Offer-Letter Intake, and
Installment Balances** — reframing the money-in side around projects (houses):
offer-letter installment schedules, computed (never stored) waterfall arrears
balances, a monthly "who's due / who's behind" dashboard, and money-out per
project. Additive over the existing engine; built manual-slice-before-AI,
docs/contracts first. See the Phase 13 section and "Semantic edges" in
`docs/master-plan.md`.

Navigation redesign (merged, PR #37): **Projects** is promoted from a Settings entry to a
top-level bottom-nav tab, so the bottom nav is now **Home · Records · Projects ·
People · Settings**. The arrears dashboard moved *inside* the Projects tab
(its "View arrears" action removed from Home); the rich monthly arrears summary
card — due/fulfilled counts + combined outstanding balance — now sits at the top
of the Projects tab instead of Home, tapping through to the full dashboard.
**Reports** is demoted from a tab to a "Reports & export" card on the Home
dashboard (this reverses the Phase 12 "Reports promoted to a top-level tab"
decision — intentional, usage-driven), and `SummaryScreen` is now a nested
back-button screen. Settings is pure config again (no "Projects & units" row).
Units (`ProjectDetailScreen`) and projects (`ProjectListScreen`) now render in a
fixed-height scroll window (a bounded `LazyColumn`) so the user can flick through
the full list without growing the page; search still appears once the list
outgrows the window. UI/navigation only — no Room/repository/backend changes.

**v1 is deployed to production (2026-06-08) and verified end-to-end.** The
backend runs on **Render** at `https://proofledger-api.onrender.com` — the
FastAPI API plus the always-on arq worker (AI extraction + the daily purge
crons), with managed **Postgres** and **Redis**. Auth is **Supabase**; proof
images live in **Cloudflare R2** (chosen for no egress fees; region `auto`, and
the S3 client forces path-style + SigV4 so any S3-compatible provider works —
`app/services/proof_storage.py`). A root `render.yaml` blueprint provisions the
whole stack; secrets live in the Render `proofledger-secrets` env group and are
never committed. `database_url` is normalized in `config.py` so a managed host's
`postgres://` string gets the `+psycopg` driver. The full flow (sign in → create
business → money in/out → attach proof → sync) was confirmed against production.
Two bugs found during release smoke-testing were fixed: houses now restore on a
fresh device (`ProjectDetailViewModel` does a local-first unit `refreshFromRemote`
on open) and picking an existing person in "Who paid?" now clears focus so the
caret stops lingering (see `docs/mobile-development-things-to-know.md`). The
Android release is signed with a real upload keystore (gitignored
`keystore.properties`; `keystore.properties.example` documents the `keytool`
steps), `versionName` 1.0.0; build the publishable bundle with
`:app:bundleRelease`. **The release AAB must be built with `API_BASE_URL` set to
the Render URL in `local.properties`** — a debug/default build points at
`10.0.2.2` and would be broken for real users. Remaining before public launch
(not code — store/compliance): host the privacy policy, complete the Play Data
Safety form, create the Play Console listing, and upload the AAB. Drafts:
`docs/privacy-policy-DRAFT.md`, `docs/play-data-safety-DRAFT.md`,
`docs/backend-deploy-guide.md`, `docs/v1-release-checklist.md`. Known follow-up:
installment **schedules** restore lazily per-unit (`ScheduleRepository.refreshForUnit`
only when a unit's schedule opens), so the arrears dashboard can read low on a
fresh device until each unit is opened — a bulk schedule restore is the proper
fix.

**Offer-letter scanning now accepts PDFs (merged to `main` via PR #43,
2026-06-09).** The Phase 13 Cut 6 offer-letter scan was originally image-only;
PR #43 added end-to-end PDF support. The Android picker is now
`OpenDocument(["image/*", "application/pdf"])`; the backend allows
`application/pdf` (validated by `%PDF-` magic bytes) **only** when
`document_type=offer_letter` — the payment-proof and share-sheet paths stay
strictly image-only — and `vision.py` sends a PDF as an Anthropic `document`
content block instead of an `image` block. Verified: backend `uv run pytest`
(222 passed), `:app:testDebugUnitTest`, `:app:assembleDebug`. **Merged is not
shipped:** the production Android AAB predates this, so PDF scanning reaches
real users only with the next `:app:bundleRelease` uploaded to Play (the backend
half may already be live if Render auto-deploys `main`). See
`docs/phase-13-offer-letter-contract.md` and "Getting an update into users'
hands" in `docs/mobile-development-things-to-know.md`.

Phase 11 (Reports and Exports) is complete. The Android app has a
"Reports & export" entry on the Home dashboard that opens a dedicated Summary
screen with a Month/Week period picker (whole periods only; previous/next
stepper, next disabled past today, plus a tap-to-jump dropdown; defaults to
current month), totals (money in/out/net, top people, top categories computed
locally from Room), offline CSV export to `cacheDir/exports`, and online PDF
export through the backend `/businesses/{business_id}/export.pdf` endpoint.
PDF generation is backend-rendered via `app/services/report_pdf.py`; Android
shares both CSV and PDF through the same FileProvider flow. Export actions log
`export_created` on-device via `SyncEventLogger`, and PDF export writes a
backend audit event. Verified on 2026-06-04 with
`:app:testDebugUnitTest`, `:app:assembleDebug`, and
`uv run pytest tests/test_phase11.py`. See "Phase 11 implementation notes" in
`docs/master-plan.md`.

Phase 10 (Offline Sync Hardening) is complete: improved sync queue with
dependency-ordered drain, retry with backoff, clear sync-status UI + a
failed-sync screen, local-first dashboard, graceful auth/session-expiry
handling, client-side event coalescing (create-then-delete collapses
locally; 404 on a queued retry is terminal), idempotent client-ID-keyed
CRUD on transactions/counterparties, optimistic-concurrency conflict checks
(`expected_updated_at` → 409), the `screenshots` `transaction_id` filter +
cursor pagination, and an `audit_events` table recording create/edit/delete
for transactions and counterparties with field-level `old_values`/
`new_values` diffs (see Phase 10 notes in `docs/master-plan.md`). Deferred
to Phase 12: a server-side `sync_events` table (sync attempts are logged
on-device for now) and the user-visible edit-history UI that reads the
audit diffs. Transaction/counterparty cursor pagination remains deferred
until true delta sync needs it.

Completed: Phases 1–6 (foundation, auth + business setup, manual ledger MVP,
counterparties, screenshot attachment, share-sheet intake), Phase 8
(server-side OCR/AI extraction via Claude Haiku 4.5), and Phase 8.1
(eager upload on attach — drafts now pre-fill during the create flow,
not only on edit-mode reopen). Amount entry now displays comma-separated
groups for both user-typed and AI-drafted amounts. A small responsiveness
audit moved proof file copy/compression/deletion and share-sheet intake
copying off the UI thread, and made the dashboard sync indicator clear
reliably after sync failures. Phase 9 added advisory duplicate warnings
using local-first checks plus backend candidates for proof hashes,
normalized references, and exact amount/date/counterparty matches. Phase 5
also got an adversarial hardening pass
— see "Phase 5 Hardening Notes" in `docs/master-plan.md` for the specific
defenses already in place so they aren't reimplemented or regressed.

Phase 7 (on-device OCR drafts) is **built but gated off** behind
`OCR_DRAFTING_ENABLED = false` in `TransactionFormViewModel.kt`. The Latin
recognizer mis-reads too many real Nigerian receipt layouts (missing ₦ glyph,
unformatted amounts, value-below-label flows) to be useful as a drafting
aid — wrong pre-fills cost the user more than blank fields. Phase 8 took
over drafting with a vision model. The OcrService / OcrDraftParser / OcrDraft
classes and tests remain in tree so the flag can be flipped on for
dogfooding without a revert.

## Proof restore notes (post-Phase 11)

Proof images now **restore on a fresh device**. Previously the app uploaded
proof but had no download path, so a fresh sign-in restored the ledger but
**not the screenshots** (the product's core proof). Android-only — the backend
already exposed the screenshot list + signed-url/content endpoints, so no
server changes.

- `ProofRepository.refreshFromRemote(businessId)` restores **metadata only**
  (the "proof attached" badges appear instantly; no metered-data byte pulls).
  Runs after transactions so each proof's denormalized fields resolve from the
  already-restored local txn. Skips orphan + soft-deleted proofs; idempotent;
  never clobbers bytes already on disk.
- Byte download is **user-gated**: `downloadAllProofsNow` (now) and
  `scheduleProofDownloadsOnWifi` (defers to a WiFi-only `ProofBackupWorker`
  via `upload()` routing). On-demand single download via `downloadProof`.
- New `ProofBackupStatus` states: `PENDING_DOWNLOAD`,
  `WAITING_FOR_WIFI_DOWNLOAD`, `DOWNLOADING`, `DOWNLOAD_FAILED`.
- UI: dashboard "Proof not on this phone" prompt (Download now / Wait for
  Wi-Fi); transaction detail shows a download placeholder + button instead of
  a broken image. Verified with `:app:testDebugUnitTest` + `:app:assembleDebug`.

## Phase 8 implementation notes

Stack: FastAPI + **arq** (Redis-backed async queue) + **Anthropic Claude
Haiku 4.5** with forced tool-use for guaranteed-shape JSON. Prompt caching
on the system prompt keeps cost down across bursts of uploads. Confidence
floor of 0.5 drops uncertain fields server-side before they reach the
client — wrong pre-fills are worse than blank ones.

Constraints:
- **Trigger**: extraction auto-enqueues on every screenshot upload (gated
  by `extraction_enabled` config + `SERVER_EXTRACTION_ENABLED` on Android).
- **Rate limit**: 30 extractions/user/hour, 5/minute via a Redis token
  bucket in `app/services/extraction.py`. Uploads themselves are never
  blocked — over-limit just sets `draft_status: "rate_limited"` and skips
  the job.
- **Retention**: `raw_json` blobs are nulled after 30 days by a daily arq
  cron (`purge_raw_json`). Per-field cleaned values are kept forever.
- **Polling**: client polls `GET /screenshots/{sid}/draft` every 5s for
  up to 60s. 404 means "not yet"; `status=ready` returns the cleaned
  fields. `ServerDraftPoller` is unit-testable without WorkManager.
- **Merge UX**: when polling completes, fields flow through the existing
  `applyDraftIfFieldsBlank` + `ocrFilledFields` field-ownership logic from
  Phase 7. User-typed fields are never clobbered.

## Phase 8.1 implementation notes (eager upload on attach)

Closes the UX hole from the original Phase 8: pre-fill now fires *during*
the create flow, not only on edit-mode reopen.

Mechanism:
- `screenshots.transaction_id` is nullable (migration `0007_screenshots_orphan`).
- The existing `POST /businesses/{bid}/screenshots` route accepts uploads
  without a `transaction_id` — those become "orphan" screenshots.
- The Android form uploads the proof the moment the user commits to it
  (picker tap, or share-sheet "Money In/Out" tap — never on share-sheet
  cancel). `TransactionFormViewModel.uploadOrphanAndPoll` runs the upload
  + poll inline, banner cycles "Drafting…" → "Draft ready" → fields fill.
- On save, the orphan id rides along in `TransactionCreateDto.screenshotId`
  and the backend links it atomically in the same DB commit as the txn
  insert via `_link_orphan_screenshot`.
- Daily arq cron `purge_orphan_screenshots` soft-deletes orphans older
  than **7 days** (run at 03:15 UTC, 15min offset from `purge_raw_json`).
  Storage bytes deleted best-effort.

Failure UX (locked in by product decision): **visible** banner, not
silent. "Draft unavailable — type the fields yourself." Trust matters
more than tidy UI — silent failures make users blame the whole app.

Offline at attach → upload fails → `pendingScreenshotId = null` → save
falls through to the **existing** `attachProof` + `ProofBackupWorker`
path. Draft becomes available on next edit-mode open (Phase 8 behavior).
No regression from the offline path.

Known limitation: **eager upload + offline save loses the link.** If the
user attaches online (orphan upload succeeds), then saves offline (txn
API call fails), the local txn is in PENDING_UPLOAD and the orphan stays
on the server unlinked. Sync retry doesn't include `screenshot_id`
because we don't persist the link intent on `LocalTransactionEntity` —
keeping the local schema migration-free for Phase 8.1 was a deliberate
trade-off. The orphan dies in the 7-day cron; the user can re-attach
the proof on next edit. Rare in practice (attach-online-save-offline
within seconds is unusual). Document, don't fix.

## Small responsiveness audit notes

Applied after Phase 8.1:
- Share-sheet image copying now runs from `MainActivity` on `Dispatchers.IO`
  so a large shared proof does not delay first UI draw.
- `ProofRepository` moves proof file prepare/delete work onto `Dispatchers.IO`
  so screenshot copy/compression cannot freeze the form while attaching,
  saving, replacing, or deleting proof images.
- Dashboard startup sync clears its "Syncing records" indicator in `finally`,
  so a thrown sync call cannot leave the app looking permanently busy.

Verified on the Android build with `:app:testDebugUnitTest` and
`:app:assembleDebug`. Deeper performance validation should still happen later
on a low-end device/emulator, not only the Pixel 7 emulator.

## Phase 9 implementation notes

Duplicate detection is advisory only. The first Save tap validates the form,
runs local duplicate checks, then best-effort backend checks. If candidates
exist, the form shows "This looks like a record you already saved." with
compact details and reason labels; "Review fields" dismisses the warning,
and "Save anyway" continues through the existing create/update path.

Matching signals:
- Same proof bytes: backend and Android compute SHA-256 over the image bytes
  and store it on screenshot rows.
- Same nonblank normalized reference number in the same business.
- Same amount, direction, exact date, and counterparty id.
- Same amount, direction, exact date, and normalized counterparty name.

Backend endpoint: `POST /businesses/{business_id}/transactions/duplicate-candidates`.
It returns at most five candidates, excludes soft-deleted rows, excludes the
current transaction on edit, and never leaks cross-business screenshot hashes.
If the backend check fails on Android, saving is not blocked; local warnings
still show when available.

## Share-sheet intake constraints (Phase 6)
- Only image MIME types are accepted: `image/jpeg`, `image/png`, `image/webp`.
  Enforced at the manifest *and* re-validated in `SharedProofIntakeRepository`
  via `contentResolver.getType()` — declared MIME from the sender is not
  trusted.
- Exactly one image per share. `ACTION_SEND_MULTIPLE` is rejected outright,
  and an `ACTION_SEND` carrying multiple ClipData items is also rejected.
  User-facing message: "Share one proof at a time."
