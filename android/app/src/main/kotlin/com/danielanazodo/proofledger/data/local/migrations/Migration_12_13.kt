package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v12 -> v13: Add `reportedAt` to `local_sync_event` (Phase 12 server-side
 * sync_events reporting). Null = not yet flushed to the backend; the
 * SyncEventReporter picks unreported rows up and posts them when online.
 */
val MIGRATION_12_13: Migration = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_sync_event ADD COLUMN reportedAt INTEGER")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_local_sync_event_reportedAt " +
                "ON local_sync_event(reportedAt)"
        )
    }
}
