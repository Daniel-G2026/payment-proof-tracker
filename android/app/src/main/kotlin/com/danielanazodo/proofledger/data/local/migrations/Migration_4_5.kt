package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_screenshot (
                id TEXT NOT NULL PRIMARY KEY,
                businessId TEXT NOT NULL,
                transactionId TEXT NOT NULL,
                localOriginalPath TEXT NOT NULL,
                localUploadPath TEXT,
                originalFileSizeBytes INTEGER NOT NULL,
                uploadFileSizeBytes INTEGER,
                mimeType TEXT NOT NULL,
                width INTEGER,
                height INTEGER,
                transactionAmountMinor INTEGER NOT NULL,
                transactionDate TEXT NOT NULL,
                transactionDirection TEXT NOT NULL,
                counterpartyId TEXT,
                counterpartyName TEXT,
                isImportant INTEGER NOT NULL,
                backupStatus TEXT NOT NULL,
                remoteScreenshotId TEXT,
                remoteStorageKey TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                lastBackupAttemptAt INTEGER,
                lastBackupError TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_screenshot_businessId ON local_screenshot(businessId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_screenshot_transactionId ON local_screenshot(transactionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_screenshot_backupStatus ON local_screenshot(backupStatus)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_screenshot_isImportant ON local_screenshot(isImportant)")
    }
}
