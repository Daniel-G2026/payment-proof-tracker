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
    PENDING_REMOTE_DELETE,

    // Restore (download) states — the mirror image of the backup states, used
    // when pulling proof bytes back down onto a fresh device. Metadata is
    // restored first so the ledger shows "proof attached" immediately; the
    // bytes follow, so we never block the ledger on a large download.
    //
    //   PENDING_DOWNLOAD          metadata restored, bytes not here yet, and
    //                             the user hasn't chosen now-vs-WiFi. Drives
    //                             the dashboard "download your proofs?" prompt.
    //   WAITING_FOR_WIFI_DOWNLOAD user chose to wait for WiFi; a WiFi-only
    //                             worker will pull the bytes when unmetered.
    //   DOWNLOADING               a download is in flight.
    //   DOWNLOAD_FAILED           the last download attempt failed; retryable.
    PENDING_DOWNLOAD,
    WAITING_FOR_WIFI_DOWNLOAD,
    DOWNLOADING,
    DOWNLOAD_FAILED,
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
