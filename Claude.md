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

## Current Phase
Phase 3: Manual Ledger MVP.
