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
- Recent polish: amount entry now uses comma-separated numbers, and a small responsiveness audit moved proof file work off the UI thread.
- Next planned phase: Phase 9 duplicate detection.

## Docs

See `docs/master-plan.md` for the full product and architecture plan.
See `docs/mobile-development-things-to-know.md` for local mobile/backend development notes.
