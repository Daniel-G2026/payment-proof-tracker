package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v16 -> v17: Add the `local_counterparty_type` table backing the synced
 * person-type picker. A clone of `local_category` (Migration_11_12) — person
 * types are promoted from the on-device metadata list to a backend-synced
 * entity so the curated list syncs across devices. Types sync down from the
 * backend (seeded with the built-in defaults + any types already in use), so
 * the table starts empty on existing installs and fills on the next refresh.
 */
val MIGRATION_16_17: Migration = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_counterparty_type (
                id TEXT NOT NULL PRIMARY KEY,
                businessId TEXT NOT NULL,
                name TEXT NOT NULL,
                normalizedName TEXT NOT NULL,
                isArchived INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER,
                syncStatus TEXT NOT NULL,
                lastSyncAttemptAt INTEGER,
                lastSyncError TEXT,
                remoteUpdatedAt TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_local_counterparty_type_businessId " +
                "ON local_counterparty_type(businessId)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_local_counterparty_type_businessId_normalizedName " +
                "ON local_counterparty_type(businessId, normalizedName)"
        )
    }
}
