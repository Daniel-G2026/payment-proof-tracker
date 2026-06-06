package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v15 -> v16: Phase 13 Cut 3 — link money-in payments to a unit.
 *
 * Adds the additive nullable `earmarkedInstallmentId` column to
 * `local_transaction` (the only stored allocation intent; null = pure
 * waterfall). `projectId` / `unitId` already landed in Migration_13_14.
 */
val MIGRATION_15_16: Migration = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_transaction ADD COLUMN earmarkedInstallmentId TEXT")
    }
}
