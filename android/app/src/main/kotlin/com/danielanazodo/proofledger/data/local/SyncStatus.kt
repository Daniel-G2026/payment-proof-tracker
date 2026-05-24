package com.danielanazodo.proofledger.data.local

import androidx.room.TypeConverter

/**
 * Master plan §8: every syncable local entity carries a sync_status. Phase 2
 * uses only [LOCAL_ONLY] (never sent), [PENDING_UPLOAD] (local write, waiting
 * for backend round-trip), and [SYNCED] (backend confirmed). Phase 10 wires
 * the retry / conflict / failed states.
 */
enum class SyncStatus {
    LOCAL_ONLY,
    PENDING_UPLOAD,
    PENDING_UPDATE,
    PENDING_DELETE,
    SYNCING,
    SYNCED,
    FAILED,
    CONFLICT,
}

class SyncStatusConverter {
    @TypeConverter
    fun toString(status: SyncStatus): String = status.name

    @TypeConverter
    fun fromString(value: String): SyncStatus = SyncStatus.valueOf(value)
}

enum class ProofBackupPreference {
    WIFI_ONLY,
    MANUAL,
    IMPORTANT_ONLY,
}

enum class ProofBackupStatus {
    SAVED_ON_PHONE_ONLY,
    PENDING_BACKUP,
    WAITING_FOR_WIFI,
    BACKING_UP,
    BACKED_UP,
    BACKUP_FAILED,
}

class ProofStatusConverter {
    @TypeConverter
    fun preferenceToString(value: ProofBackupPreference): String = value.name

    @TypeConverter
    fun preferenceFromString(value: String): ProofBackupPreference =
        ProofBackupPreference.valueOf(value)

    @TypeConverter
    fun backupStatusToString(value: ProofBackupStatus): String = value.name

    @TypeConverter
    fun backupStatusFromString(value: String): ProofBackupStatus =
        ProofBackupStatus.valueOf(value)
}
