package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v6 -> v7: Phase 7 (Local OCR Drafts). Adds a nullable ocrText column to
 * local_screenshot so we can persist the raw on-device OCR output alongside
 * the proof image. Existing rows get NULL — they were attached before OCR
 * was wired in, so re-OCRing them would be needless work.
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_screenshot ADD COLUMN ocrText TEXT")
    }
}
