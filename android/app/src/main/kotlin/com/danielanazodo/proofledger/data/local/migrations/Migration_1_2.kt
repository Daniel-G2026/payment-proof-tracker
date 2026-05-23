package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: add LocalProfile + LocalBusiness for Phase 2. local_app_metadata
 * (created in v1) is untouched.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_profile (
                id TEXT NOT NULL PRIMARY KEY,
                fullName TEXT,
                phone TEXT,
                email TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                syncStatus TEXT NOT NULL,
                lastSyncAttemptAt INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_business (
                id TEXT NOT NULL PRIMARY KEY,
                ownerId TEXT NOT NULL,
                name TEXT NOT NULL,
                country TEXT NOT NULL,
                currency TEXT NOT NULL,
                businessType TEXT,
                plan TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                syncStatus TEXT NOT NULL,
                lastSyncAttemptAt INTEGER
            )
            """.trimIndent()
        )
    }
}
