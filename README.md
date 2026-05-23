# Payment Proof Tracker

Android-native, offline-first payment proof tracker for small businesses.

The app helps small businesses organize payment screenshots into a searchable business ledger.

## Core Promise

Upload or share payment screenshots, confirm the extracted details, and keep a clean record of who paid, who was paid, and what money moved.

## Initial Target Market

Small Nigerian construction-adjacent businesses, suppliers, subcontractors, and family-run businesses that currently track payments through WhatsApp screenshots, notebooks, spreadsheets, and memory.

## Tech Direction

- Android: Kotlin + Jetpack Compose
- Local storage: Room SQLite
- Background sync: WorkManager
- OCR: Google ML Kit first, server-side AI/OCR later
- Backend: FastAPI
- Database: Postgres
- Storage: S3-compatible or Supabase Storage

## Docs

See `docs/master-plan.md` for the full product and architecture plan.