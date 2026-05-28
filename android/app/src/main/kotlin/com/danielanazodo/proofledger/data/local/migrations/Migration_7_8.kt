package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v7 -> v8: Phase 8 (Server-Side OCR/AI Extraction). Adds three nullable
 * columns to local_screenshot for tracking the server extraction job:
 *
 *  - serverDraftStatus: latest job state we've seen
 *    (queued / processing / ready / failed / rate_limited). Lets the form
 *    show a "draft ready / failed" hint without re-polling when the user
 *    re-opens an existing transaction.
 *  - serverDraftFetchedAt: epoch ms when we last successfully read the draft
 *    from the server. Drives staleness checks.
 *  - serverDraftError: short human-readable error (e.g. "Couldn't read this
 *    one") if the job ended in a terminal failure.
 *
 * Existing rows get NULL on all three — those proofs were uploaded before
 * server extraction existed, so they simply have no draft to track. The
 * form treats null as "no draft" and falls back to a blank form.
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_screenshot ADD COLUMN serverDraftStatus TEXT")
        db.execSQL("ALTER TABLE local_screenshot ADD COLUMN serverDraftFetchedAt INTEGER")
        db.execSQL("ALTER TABLE local_screenshot ADD COLUMN serverDraftError TEXT")
    }
}
