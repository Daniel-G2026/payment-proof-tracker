package com.danielanazodo.proofledger.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v9 -> v10: Phase 10 local sync/audit event log. This is internal
 * diagnosability data for the future failed-sync screen, not user accounting.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_sync_event (
                id TEXT NOT NULL,
                businessId TEXT NOT NULL,
                entityType TEXT NOT NULL,
                entityId TEXT,
                action TEXT NOT NULL,
                status TEXT NOT NULL,
                errorMessage TEXT,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_sync_event_businessId ON local_sync_event(businessId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_sync_event_entityType ON local_sync_event(entityType)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_sync_event_entityId ON local_sync_event(entityId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_sync_event_action ON local_sync_event(action)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_sync_event_status ON local_sync_event(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_sync_event_createdAt ON local_sync_event(createdAt)")
    }
}
