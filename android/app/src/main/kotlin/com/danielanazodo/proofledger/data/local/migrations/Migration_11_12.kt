package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v11 -> v12: Add the `local_category` table backing the structured category
 * picker (Cut 2 of structured categories). Mirrors `local_counterparty` minus
 * type/phone/notes, plus an `isArchived` flag. Categories sync down from the
 * backend (seeded with construction defaults), so the table starts empty on
 * existing installs and fills on the next refresh.
 */
val MIGRATION_11_12: Migration = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_category (
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
            "CREATE INDEX IF NOT EXISTS index_local_category_businessId ON local_category(businessId)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_local_category_businessId_normalizedName " +
                "ON local_category(businessId, normalizedName)"
        )
    }
}
