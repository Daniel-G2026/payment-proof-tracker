package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_transaction (
                id TEXT NOT NULL PRIMARY KEY,
                businessId TEXT NOT NULL,
                amountMinor INTEGER NOT NULL,
                currency TEXT NOT NULL,
                direction TEXT NOT NULL,
                transactionDate TEXT NOT NULL,
                counterpartyName TEXT,
                category TEXT,
                note TEXT,
                bankName TEXT,
                referenceNumber TEXT,
                source TEXT NOT NULL,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER,
                syncStatus TEXT NOT NULL,
                lastSyncAttemptAt INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_transaction_businessId ON local_transaction(businessId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_transaction_direction ON local_transaction(direction)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_transaction_transactionDate ON local_transaction(transactionDate)")
    }
}
