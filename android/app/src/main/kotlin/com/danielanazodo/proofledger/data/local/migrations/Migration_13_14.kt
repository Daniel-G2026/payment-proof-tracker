package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v13 -> v14: Phase 13 (Projects, Offer-Letter Intake & Installment Balances).
 *
 * One coherent migration introducing all of Cut 1's local schema (the plan
 * deliberately avoids a multi-version cascade):
 *  - `local_project` — developments/estates.
 *  - `local_unit` — houses/plots within a project, optionally pointing at a
 *    buyer counterparty.
 *  - additive nullable `projectId` / `unitId` columns on `local_transaction`
 *    (money-out → project, money-in → unit; wired in Cut 3 / Cut 5).
 *
 * Projects/units sync down from the backend, so the new tables start empty on
 * existing installs and fill on the next refresh. Existing transactions get
 * NULL project/unit, which is the intended "general / no-project" bucket.
 */
val MIGRATION_13_14: Migration = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_project (
                id TEXT NOT NULL PRIMARY KEY,
                businessId TEXT NOT NULL,
                name TEXT NOT NULL,
                note TEXT,
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
            "CREATE INDEX IF NOT EXISTS index_local_project_businessId ON local_project(businessId)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_unit (
                id TEXT NOT NULL PRIMARY KEY,
                businessId TEXT NOT NULL,
                projectId TEXT NOT NULL,
                label TEXT NOT NULL,
                buyerCounterpartyId TEXT,
                totalPriceMinor INTEGER,
                currency TEXT NOT NULL,
                status TEXT NOT NULL,
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
            "CREATE INDEX IF NOT EXISTS index_local_unit_businessId ON local_unit(businessId)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_local_unit_projectId ON local_unit(projectId)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_local_unit_buyerCounterpartyId " +
                "ON local_unit(buyerCounterpartyId)"
        )

        db.execSQL("ALTER TABLE local_transaction ADD COLUMN projectId TEXT")
        db.execSQL("ALTER TABLE local_transaction ADD COLUMN unitId TEXT")
    }
}
