package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v8 -> v9: Phase 9 duplicate detection. Store the SHA-256 of the preserved
 * proof image so local duplicate checks can warn on exact same-proof reuse.
 * Legacy rows get NULL and continue matching by reference / amount details.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_screenshot ADD COLUMN imageSha256 TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_screenshot_imageSha256 ON local_screenshot(imageSha256)")
    }
}
