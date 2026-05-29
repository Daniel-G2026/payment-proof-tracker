package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v10 -> v11: Track the server-issued `updated_at` on transactions and
 * counterparties so the client can send it as `expected_updated_at` on PATCH
 * for optimistic concurrency. Nullable — rows that have never round-tripped
 * to the server simply skip the check.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_transaction ADD COLUMN remoteUpdatedAt TEXT")
        db.execSQL("ALTER TABLE local_counterparty ADD COLUMN remoteUpdatedAt TEXT")
    }
}
