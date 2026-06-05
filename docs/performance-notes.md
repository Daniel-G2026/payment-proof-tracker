# Performance Notes (Phase 12)

Status of the Phase 12 "cold-start + list performance" item. This records what
is already in good shape from a **code** standpoint and what still requires
**on-device measurement** (which can't be done from CI / a dev box alone).

## Already in good shape (verified by inspection)

- **Cold start is lean.** `PaymentProofApp.onCreate` does only fast work on the
  main thread: WorkManager schedule (idempotent enqueue), a guarded Sentry init
  (no-op when no DSN, so debug/CI pay nothing), and a non-blocking analytics
  `app_opened` (NoOp when no key). The stuck-backup recovery runs on
  `Dispatchers.IO` via `appScope.launch`.
- **Off-main-thread I/O.** A prior responsiveness audit moved proof file
  copy/compression/deletion and share-sheet intake copying off the UI thread
  (see CLAUDE.md "Small responsiveness audit notes").
- **Stable list keys.** Every `LazyColumn { items(...) }` supplies a stable
  `key` (`BusinessSelectScreen`, `EditHistoryScreen`), so item identity is
  preserved across recompositions and scrolling doesn't rebind needlessly.
- **Image compression** already exists in `ProofFileStore` (Phase 8.x).

## Still requires on-device measurement (not code-complete)

These need a real low-end device / emulator + profiling; do them before a
pilot on lower-end hardware:

1. **Cold-start timing** on a low-end device (e.g. via a Macrobenchmark module
   + a Baseline Profile). Generating a Baseline Profile requires running the
   benchmark on a device, so it's deliberately not committed blind here.
2. **Long transaction-list rendering.** The Records / counterparty / summary
   lists currently render grouped cards via `forEach` inside a single lazy
   `item {}` (not virtualized). For users with hundreds of records this should
   be measured, and if it janks, converted to virtualized `items()` (or paged).
   Left as a measured decision rather than a blind refactor, since it changes
   the grouped-card visuals.
3. **Image compression timing** on a large proof on a low-end device — confirm
   the compression step doesn't stall the attach flow on slow hardware.

## Recommendation

Treat code-level perf as done for v1. Schedule one profiling session on a
representative low-end device before the pilot; only then decide on
list virtualization/paging and a committed Baseline Profile.
