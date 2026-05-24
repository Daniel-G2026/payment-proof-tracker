package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v5 -> v6: surface sync failures. Adds a nullable lastSyncError column to the
 * three syncable tables so the UI can show why a row is stuck in FAILED.
 * No data is rewritten; existing rows get NULL.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_transaction ADD COLUMN lastSyncError TEXT")
        db.execSQL("ALTER TABLE local_business ADD COLUMN lastSyncError TEXT")
        db.execSQL("ALTER TABLE local_counterparty ADD COLUMN lastSyncError TEXT")
    }
}
