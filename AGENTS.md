# AGENTS.md

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
Phase 10: Offline Sync Hardening.

Focus for this phase:
- Sync local pending work across every business, not only the selected business.
- Preserve dependency order: businesses, then people, then records, then proof backup.
- Keep failed local records retryable with clear user-facing errors.
- Harden proof lifecycle, retry behavior, and audit/telemetry before adding new product features.
