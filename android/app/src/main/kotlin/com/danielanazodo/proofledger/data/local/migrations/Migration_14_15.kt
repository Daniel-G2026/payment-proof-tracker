package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v14 -> v15: Phase 13 Cut 2 — versioned schedules.
 *
 *  - `local_schedule_version` — one versioned offer-letter schedule per unit
 *    (renegotiation creates a new active version; old ones kept).
 *  - `local_installment` — the scheduled lines (amount + due date) of a version.
 *    No fill columns; fills are computed by the allocation engine.
 *
 * Both sync down from the backend, so the tables start empty on existing
 * installs and fill on the next refresh.
 */
val MIGRATION_14_15: Migration = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_schedule_version (
                id TEXT NOT NULL PRIMARY KEY,
                businessId TEXT NOT NULL,
                unitId TEXT NOT NULL,
                versionNo INTEGER NOT NULL,
                source TEXT NOT NULL,
                isActive INTEGER NOT NULL,
                offerLetterScreenshotId TEXT,
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
            "CREATE INDEX IF NOT EXISTS index_local_schedule_version_businessId " +
                "ON local_schedule_version(businessId)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_local_schedule_version_unitId " +
                "ON local_schedule_version(unitId)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_installment (
                id TEXT NOT NULL PRIMARY KEY,
                businessId TEXT NOT NULL,
                scheduleVersionId TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                amountMinor INTEGER NOT NULL,
                dueDate TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_local_installment_businessId " +
                "ON local_installment(businessId)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_local_installment_scheduleVersionId " +
                "ON local_installment(scheduleVersionId)"
        )
    }
}
