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

## Architecture Boundaries
- UI polish and redesign are expected later, including settings, multiple businesses, tabs, and navigation changes.
- Keep infrastructure explicitly separate from UI so redesigns do not require storage/network rewrites.
- Compose screens should not call Room DAOs, Retrofit/API services, WorkManager, file storage, or token storage directly.
- Screens should read ViewModel state and send simple user events.
- ViewModels may coordinate screen behavior, validation, and repository calls, but heavy data, sync, backup, auth, and file logic belongs in repositories/services.
- Infrastructure classes must not depend on Compose or screen layout.
- If UI state becomes complex, add dedicated UI state/models instead of bending database entities around presentation needs.

## Current Phase
Phase 9: Duplicate Detection (next).

Completed: Phases 1–6 (foundation, auth + business setup, manual ledger MVP,
counterparties, screenshot attachment, share-sheet intake) and Phase 8
(server-side OCR/AI extraction via Claude Haiku 4.5). Phase 5 also got an
adversarial hardening pass — see "Phase 5 Hardening Notes" in
`docs/master-plan.md` for the specific defenses already in place so they
aren't reimplemented or regressed.

Phase 7 (on-device OCR drafts) is **built but gated off** behind
`OCR_DRAFTING_ENABLED = false` in `TransactionFormViewModel.kt`. The Latin
recognizer mis-reads too many real Nigerian receipt layouts (missing ₦ glyph,
unformatted amounts, value-below-label flows) to be useful as a drafting
aid — wrong pre-fills cost the user more than blank fields. Phase 8 took
over drafting with a vision model. The OcrService / OcrDraftParser / OcrDraft
classes and tests remain in tree so the flag can be flipped on for
dogfooding without a revert.

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

Architectural compromise — **drafting fires on edit-mode form open, not at
attach time**. The current form attaches the proof in UI state but doesn't
upload it until `save()` runs, so there's nothing on the server to poll
during the new-transaction flow. `TransactionFormViewModel.tryServerDraftMergeFor`
runs the poller when the user re-opens a transaction whose proof has been
uploaded but whose `serverDraftStatus` is not yet `ready`. The draft is
applied on second open. Eager-upload-on-attach (for in-form draft during
the first create flow) is left as a follow-up — it's a non-trivial refactor
because the proof has to be uploaded without a parent transaction.

## Share-sheet intake constraints (Phase 6)
- Only image MIME types are accepted: `image/jpeg`, `image/png`, `image/webp`.
  Enforced at the manifest *and* re-validated in `SharedProofIntakeRepository`
  via `contentResolver.getType()` — declared MIME from the sender is not
  trusted.
- Exactly one image per share. `ACTION_SEND_MULTIPLE` is rejected outright,
  and an `ACTION_SEND` carrying multiple ClipData items is also rejected.
  User-facing message: "Share one proof at a time."
