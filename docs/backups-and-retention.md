# Backups & Data Retention

Operational policy for the Payment Proof Tracker. This is a documentation
deliverable from Phase 12 (UX polish + reliability); it records what is backed
up, where, and for how long, so a pilot can be run responsibly.

## What data exists

| Data | Store | Notes |
| --- | --- | --- |
| Transactions, counterparties, categories | Postgres (`transactions`, `counterparties`, `categories`) + on-device Room mirror | Soft-deleted (`deleted_at`) — rows are retained, not hard-deleted. |
| Proof images | Object storage (S3-compatible) or local `storage/proofs` in dev | Original bytes preserved; never overwritten. |
| AI draft blobs | `transaction_drafts.raw_json` | **Purged after 30 days** by a daily cron; cleaned per-field values kept. |
| Orphan screenshots | `screenshots` with `transaction_id = NULL` | **Soft-deleted after 7 days** by a daily cron. |
| Audit log | `audit_events` | Field-level create/edit/delete diffs. Retained indefinitely (powers edit history). |
| Sync attempts | On-device `SyncEventLogger` | Local only until the deferred server-side `sync_events` table lands. |

## Backups

- **Database:** rely on the managed Postgres provider's automated daily
  snapshots + point-in-time recovery (PITR). Verify the retention window
  (recommend ≥ 7 days) is enabled on the project before the pilot.
- **Object storage (proofs):** enable bucket versioning and a lifecycle policy.
  Proof images are the product's core value — treat them as the highest-priority
  asset. Do **not** set an aggressive expiration lifecycle on proof objects.
- **On-device:** the Room DB and proof files are a cache/mirror of the backend.
  A fresh device restores the ledger and (Phase 11 follow-up) proof metadata +
  user-gated byte download. The phone is **not** a backup of record.

## Retention summary

- Ledger data: retained indefinitely (soft-delete only).
- Proof images: retained indefinitely; deletion is user-initiated and
  best-effort propagated to storage.
- AI `raw_json`: 30 days.
- Orphan screenshots: 7 days.
- Audit/edit-history: indefinite.

## Restore drill (recommended before pilot)

1. Take a manual DB snapshot; confirm it appears in the provider console.
2. Restore it into a scratch database and boot the API against it.
3. Confirm a known transaction + its proof object resolve via a signed URL.
4. On a clean device install, sign in and confirm the ledger + proof badges
   restore, then user-gated proof download fetches the bytes.

## Open items (tracked for later phases)

- Server-side `sync_events` table + ingestion (deferred from Phase 10/12).
- Encrypted local database / proof directory (master-plan §20.7) before
  handling highly sensitive pilots.
