# Lessons & Gotchas — things that got missed in planning

A cross-project companion to `mobile-development-things-to-know.md`. That doc
is the **operational** reference (how to run the backend, emulator networking,
R8, compression policy). **This** doc is the **pre-mortem**: the classes of
problem that did *not* show up in the plan and only surfaced mid-build — so the
next app can plan for them up front.

How to use it: skim the **Plan-for-it** lines before designing a new app. Each
entry is written so it transfers to a different codebase, with the concrete
example from this project (an offline-first Android + FastAPI payment-proof
tracker) kept as evidence.

> The single most useful meta-lesson: **a feature is not "done" until the
> *reverse* of it is also done.** Upload without download, create without
> restore, write without read-back, online without offline. We shipped proof
> *upload* in an early phase and only discovered months later that proof
> *download* (restore onto a new device) never existed — for an app whose whole
> value is "the proof is preserved." Every data-movement feature has a return
> trip; put it on the plan the moment you plan the outbound leg.

---

## 1. Offline-first & sync

These bite hardest because the happy path (online, one device) hides them.

- **Sync is bidirectional; "restore" is a separate feature from "backup."**
  Pushing local→server does not give you server→local. On a fresh device (new
  phone, reinstall, cleared data) the user expects *everything* back. Plan a
  pull/rehydrate path per entity, not just an upload queue.
  *Here:* text restored via `refreshFromRemote`, but proof images had no
  download path for months. → **Plan-for-it:** for every entity, write down
  both "how it gets to the server" and "how it comes back to a blank device,"
  in the same planning pass.

- **Restore order follows data dependencies.** Children need their parents
  present locally first.
  *Here:* counterparties → transactions → proofs. Proof rows carry denormalized
  transaction fields, so proofs must restore *after* transactions or the fields
  can't resolve. → **Plan-for-it:** define a strict dependency order and make
  restore skip-and-retry when a parent isn't local yet (idempotent re-runs).

- **Denormalized snapshot fields must be re-populated on restore.** If a row
  copies fields off another (e.g. `counterpartyName` stored next to
  `counterpartyId`), a naive restore that only reads one source leaves them
  blank. → **Plan-for-it:** for each denormalized field, decide where it comes
  from during restore.

- **Idempotency via client-generated IDs.** Retries, double-taps, and
  at-least-once delivery will replay writes. If the client mints the row's UUID
  and the server upserts on it, a replay is a no-op instead of a duplicate.
  *Here:* client-ID-keyed CRUD on transactions/counterparties/screenshots.
  → **Plan-for-it:** generate IDs on the client; make create endpoints
  upsert-on-id.

- **Optimistic-concurrency conflict detection.** Two devices edit the same row
  offline; last-write-wins silently destroys data. Send the expected version
  (`expected_updated_at`) and return **409** on mismatch so the client can
  reconcile. → **Plan-for-it:** every editable row needs a version/timestamp and
  a conflict path, even if v1's reconciliation is just "ask the user."

- **Coalesce queued events before sending.** A row created *then* deleted while
  offline should collapse locally and never hit the server. A queued retry that
  gets a **404** is terminal (the thing is already gone) — don't loop forever.
  → **Plan-for-it:** define terminal vs retryable outcomes for each queued op up
  front.

- **Never silently downgrade data to make a sync succeed.** If a transaction
  references a counterparty that hasn't synced, keep it pending — do **not**
  drop it to a plain name to force the upload through. → **Plan-for-it:** "block
  and stay pending" beats "succeed by losing information."

- **Cursor pagination + timestamp precision mismatch.** SQLite stored
  second-precision timestamps while the ORM bound microsecond Python datetimes,
  causing duplicate/last pages at boundaries. → **Plan-for-it:** normalize
  timestamp precision on both sides of a keyset cursor; test the page boundary
  explicitly.

- **Sign-out should not always wipe local data.** Same-user sign-out→sign-in is
  the common case; wiping forces a full re-download (and, before restore
  existed, *destroyed* proof). Only wipe when a *different* user signs in on the
  device. → **Plan-for-it:** separate "sign out" (keep cache) from "switch user"
  (wipe + cascade cleanup).

- **Show what's not backed up.** Offline-first means a record can exist only on
  the phone. If the phone is lost before it syncs, it's gone. The UI must make
  "X records not backed up yet" visible so the user knows what's safe. →
  **Plan-for-it:** a per-row sync state and an at-a-glance "unsynced count."

---

## 2. Media / files / proof

Binary data behaves nothing like rows.

- **Treat the original as sacred; derive copies.** Keep the user's original
  file locally and untouched; compress a *separate* upload copy. Only use the
  compressed copy if it's actually smaller. → **Plan-for-it:** never overwrite
  the original unless the user explicitly opts into a space-saver.

- **Downloads cost the user money.** On metered mobile data, auto-downloading
  every image on restore is hostile. Restore *metadata* instantly (so the UI
  shows "attached"), then download bytes **on the user's terms** (now / on
  Wi-Fi / on open). *Here:* a "download now vs wait for Wi-Fi" prompt that
  appears only when there are un-downloaded proofs. → **Plan-for-it:** decouple
  metadata sync from byte transfer; gate bytes behind user choice + Wi-Fi.

- **Workers get killed mid-transfer.** A background upload/download interrupted
  by OOM/force-stop leaves a row stuck "in progress" forever. Recover stuck rows
  on app start (reset to pending + re-enqueue). → **Plan-for-it:** a startup
  reconciliation pass for any "…ing" state.

- **Orphaned blobs need a janitor.** Files uploaded but never linked
  (abandoned forms), or stale temp copies, accumulate. *Here:* server crons
  purge orphan screenshots (7 days) and raw AI JSON (30 days); the client
  cleans temp copies on failed upload. → **Plan-for-it:** every "uploaded but
  not yet linked" state needs a TTL cleanup.

- **Serve private files via short-lived signed URLs**, not public paths, with
  signature verification on the content endpoint. → **Plan-for-it:** decide the
  proof-serving auth model before the first upload ships.

- **Display reads from the local file, so a metadata-only row shows a broken
  image.** Guard the viewer: if the local file is absent, show a "not on this
  device — download" placeholder instead of a broken `AsyncImage`. →
  **Plan-for-it:** every media view needs a "present but not downloaded" state.

---

## 3. AI / OCR features

- **Validate extraction on *real* user data early, not clean samples.**
  *Here:* on-device OCR mis-read real Nigerian receipts (missing ₦ glyph,
  value-below-label layouts, unformatted amounts) badly enough to be built and
  then **gated off**; a server vision model replaced it. → **Plan-for-it:**
  collect real, messy inputs before committing to an extraction approach.

- **Wrong autofill is worse than a blank field.** A confidently-wrong pre-fill
  costs the user more (they must notice and undo it) than an empty one. Drop
  low-confidence fields server-side (a confidence floor) before they reach the
  UI. → **Plan-for-it:** AI output is a *draft*; require user confirmation, and
  prefer blanks to guesses.

- **Never let the AI step block the core action.** Extraction is best-effort:
  if the queue is down or rate-limited, the upload/save still succeeds; the user
  just doesn't get a draft. → **Plan-for-it:** AI is an enhancement path, never
  on the critical path.

- **Rate-limit and retain deliberately.** A token bucket (per-user/hour and
  /minute) caps cost; raw model output is purged on a TTL for
  privacy/storage while cleaned fields are kept. → **Plan-for-it:** decide
  cost-control + data-retention before turning a paid model loose on uploads.

- **Keep a gated-off feature in-tree instead of reverting it.** The dead OCR
  path stayed behind a flag so it can be dogfooded without a git revert. →
  **Plan-for-it:** feature-flag uncertain features so killing them is one
  boolean, not a code excavation.

---

## 4. Backend input validation & security

- **Never trust client-declared content type.** The multipart `Content-Type`
  is attacker-controlled. Allowlist the MIME *and* sniff magic bytes; reject a
  `.exe` labeled `image/jpeg` at ingest. *Here:* JPEG/PNG/WebP magic-byte
  checks, and `image/svg+xml` is explicitly disallowed (XML with executable
  script). → **Plan-for-it:** validate uploads by content, not by label;
  allowlist, don't `startswith("image/")`.

- **Cap request size twice.** Reject early by `Content-Length` when present,
  then enforce a hard cap while streaming the body (a lying or absent header
  shouldn't let you buffer 100 MB). → **Plan-for-it:** size limits at both the
  header and the stream.

- **Cap numeric inputs to your storage's range.** An unbounded `width` field
  overflowed Postgres `INTEGER` and 500'd on commit. *Here:* pixel dimensions
  capped well under the int max. → **Plan-for-it:** validate numeric ranges
  against the actual column type.

- **Re-validate at every layer.** The share-sheet MIME allowlist is enforced in
  the manifest *and* re-checked via `contentResolver.getType()` — the sender's
  declared type isn't trusted. → **Plan-for-it:** defense in depth; the
  manifest/UI filter is a hint, not a guarantee.

- **Distinguish terminal from retryable HTTP failures.** 401/403 (auth), 413
  (too big), 415 (wrong type) won't change on retry — surface them; 408/425/429
  and 5xx are retryable. → **Plan-for-it:** a status→action table for the sync
  worker so it neither loops on hopeless errors nor gives up on transient ones.

---

## 5. Data modeling

- **Money is integer minor units, never floats.** Store kobo/cents as `Long`;
  format for display only. → **Plan-for-it:** integer minor units from day one;
  retrofitting is painful.

- **Soft-delete everywhere, and remember to exclude it everywhere.** A
  `deleted_at` column means *every* query, aggregate, duplicate-check, and
  restore must filter it out, and deletes must cascade (e.g. proof cleanup when
  a transaction is deleted). → **Plan-for-it:** soft-delete is a cross-cutting
  contract, not a single column.

- **Enums stored as strings are migration-free to extend, but `valueOf` throws
  on unknown values.** Adding states needs no DB migration; but old clients
  reading a new value (or a typo) will crash. *Here:* adding download states to
  a proof-status enum needed no migration, and the compiler's exhaustive-`when`
  check flagged every place to update. → **Plan-for-it:** lean on
  exhaustive-`when`; for forward compatibility across client versions, parse
  unknown enum strings defensively.

- **Free-text fields fragment every aggregate built on them.** A typed
  "Category" produces `Transport` / `transport` / `transprot` as three report
  buckets, and an unlabeled text box also fails to *teach* the user what it's
  for. → **Plan-for-it:** for anything you'll group/report on, use a normalized
  value or a pick-list, not raw text.

- **Niche-first, but keep the schema generic.** Tailor to one vertical via
  **seed data and language**, not by baking the vertical into tables — then
  "go general later" is a content change, not a migration. → **Plan-for-it:**
  put domain specificity in seeds/config; keep storage neutral. (Also: users
  won't configure software they don't understand — ship opinionated defaults,
  make customization an escape hatch, not a setup tax.)

---

## 6. Android platform specifics

- **Audit for main-thread I/O.** File copy, image compression, hashing, and
  share-sheet intake copying will jank or freeze the UI if left on the main
  dispatcher. *Here:* a responsiveness pass moved all of it to `Dispatchers.IO`.
  → **Plan-for-it:** any file/crypto/bitmap work goes off the main thread by
  default.

- **Clear busy indicators in `finally`.** A sync call that throws left the
  dashboard showing "Syncing…" forever. → **Plan-for-it:** reset transient UI
  state in `finally`, never only on the success path.

- **WorkManager constraints mean "later," not "now."** A Wi-Fi-only job waits
  for unmetered network; a user tapping an *immediate* action needs instant
  feedback + a clear failure, not a silent enqueue. → **Plan-for-it:** separate
  "do it now" (foreground, immediate feedback) from "queue it" (constrained
  worker).

- **The emulator is not the phone.** `localhost` ≠ your machine (use
  `10.0.2.2`), Wi-Fi/metered constraints differ, and you must rebuild after
  changing `local.properties` or DI. (See the operational doc for the full
  checklist.) → **Plan-for-it:** smoke-test on a real device before trusting
  network/constraint behavior.

- **R8/release strips reflection-based code silently.** Moshi adapters, Hilt,
  serialization can break only in release. (Full failure-mode list in the
  operational doc.) → **Plan-for-it:** smoke-test a *release* build whenever you
  add a reflection-using library or touch the minify config.

---

## 7. Product & UX trust

- **Fail visibly, not silently.** A silent failure makes the user blame the
  whole app. *Here (locked-in product rule):* a draft that can't load shows
  "Draft unavailable — type the fields yourself," not a blank that looks like a
  bug. → **Plan-for-it:** every failure has a user-readable message and a
  next-step.

- **Locale/glyph traps in output.** The ₦ glyph isn't in ReportLab's base-14
  fonts, so PDFs rendered tofu boxes → switched to a `"NGN "` prefix.
  Amount entry needed comma grouping for both typed and AI-filled values. →
  **Plan-for-it:** verify currency symbols/number formatting in *every* output
  surface (PDF, CSV, share text), not just the main form.

- **Constrain the intake surface.** Share-sheet accepts exactly one image of an
  allowed type; `ACTION_SEND_MULTIPLE` and multi-item `ACTION_SEND` are rejected
  with "Share one proof at a time." → **Plan-for-it:** decide what you *won't*
  accept, and say so clearly.

---

## 8. Process, tooling & collaboration

- **Document known limitations instead of over-fixing.** Some edge cases are
  rare enough to write down rather than solve. *Here:* "eager upload + offline
  save loses the proof link" is documented with the rationale (keeping the local
  schema migration-free was the deliberate trade-off) rather than fixed. →
  **Plan-for-it:** a "known limitations + why" section beats silent gaps or
  gold-plating.

- **Test fakes that implement a full interface are a maintenance tax.** Adding
  one method to a Retrofit/DAO interface broke every hand-written fake across
  the test suite. → **Plan-for-it:** expect this cost; keep interfaces narrow,
  or use a shared fake/base.

- **Keep one source of truth for project rules, and make every tool read it.**
  `CLAUDE.md` drives the AI assistant; Cursor needed its own
  `.cursor/rules/*.mdc` pointing back at it (with "if conflict, CLAUDE.md wins")
  so the rules don't drift. → **Plan-for-it:** one rules doc, thin per-tool
  shims that defer to it.

- **Branch-and-PR even solo.** One feature per branch off `main`, PR in, never
  commit to `main`. Long-lived feature branches drift — merge `main` in
  periodically. Delete merged branches. → **Plan-for-it:** the discipline pays
  off the first time you need to bisect or revert one feature cleanly.

- **Build one phase at a time; don't build future-phase features early.**
  Inspect the repo and list files to change *before* coding; run the relevant
  build/tests *after*. → **Plan-for-it:** a phase plan with explicit "not now"
  scope keeps cuts shippable and reviewable.

- **(Windows) git line endings.** Expect `LF will be replaced by CRLF`
  warnings; set `core.autocrlf` deliberately so diffs aren't noisy.

---

*Generated from the payment-proof-tracker build. Keep it portable — drop it into
a new project's planning session alongside `mobile-development-things-to-know.md`.*
