package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_counterparty (
                id TEXT NOT NULL PRIMARY KEY,
                businessId TEXT NOT NULL,
                name TEXT NOT NULL,
                normalizedName TEXT NOT NULL,
                type TEXT NOT NULL,
                phone TEXT,
                notes TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER,
                syncStatus TEXT NOT NULL,
                lastSyncAttemptAt INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("ALTER TABLE local_transaction ADD COLUMN counterpartyId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_counterparty_businessId ON local_counterparty(businessId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_counterparty_businessId_normalizedName ON local_counterparty(businessId, normalizedName)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_counterparty_type ON local_counterparty(type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_transaction_counterpartyId ON local_transaction(counterpartyId)")
    }
}
