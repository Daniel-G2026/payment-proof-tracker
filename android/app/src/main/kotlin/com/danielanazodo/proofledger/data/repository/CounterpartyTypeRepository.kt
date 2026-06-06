package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.dao.LocalCounterpartyDao
import com.danielanazodo.proofledger.data.local.dao.LocalCounterpartyTypeDao
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyTypeEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyTypeCreateDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyTypeDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyTypeUpdateDto
import com.danielanazodo.proofledger.data.sync.AUTH_EXPIRED_SYNC_MESSAGE
import com.danielanazodo.proofledger.data.sync.SyncEventLogger
import com.danielanazodo.proofledger.data.sync.isSyncConflictFailure
import com.danielanazodo.proofledger.data.sync.toSyncErrorMessage
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.HttpException

/**
 * The managed list of person *types* (customer, supplier, subcontractor,
 * buyer…) a business offers when adding/editing people. Promoted in Phase 13
 * from an on-device-only metadata list to a first-class, backend-synced entity
 * — so the curated list now syncs across devices, exactly like categories. A
 * lighter sibling of [CategoryRepository] with the same offline-first sync
 * machinery.
 *
 * Counterparties keep storing their `type` as a plain snake_case string
 * snapshot, so this table only feeds the picker — deleting a type never breaks
 * an existing person. `observeTypes` returns those snake_case keys; the UI
 * derives a Title-Case label for display.
 *
 * The public surface (observeTypes/seedIfNeeded/addType/removeType) is
 * unchanged from the metadata-backed version so the ViewModels and screens
 * need no changes.
 */
@Singleton
class CounterpartyTypeRepository @Inject constructor(
    private val api: ApiService,
    private val localDao: LocalCounterpartyTypeDao,
    private val counterpartyDao: LocalCounterpartyDao,
    private val metadataDao: LocalAppMetadataDao,
    private val syncEventLogger: SyncEventLogger,
) {
    fun observeTypes(businessId: String): Flow<List<String>> =
        localDao.observeByBusiness(businessId).map { rows -> rows.map { it.normalizedName }.distinct() }

    /**
     * First-use initialization for this device. Pulls the synced types from the
     * backend (best-effort, offline-tolerant), then — exactly once per device,
     * guarded by a marker — seeds the built-in defaults plus any types found in
     * the legacy on-device metadata list and on the business's existing people.
     *
     * The one-time legacy import (see class doc) carries over custom types that
     * only ever lived in the pre-sync metadata list. It uses deterministic,
     * per-business ids (same scheme as the backend seed/backfill), so a create
     * that races the backend's own seed lands as an idempotent upsert rather
     * than a duplicate. Known rare edge: a type the user created but never
     * assigned to a person AND that isn't in the metadata list won't carry over.
     */
    suspend fun seedIfNeeded(businessId: String) {
        // Pull first so the import below dedupes against already-synced rows.
        refreshFromRemote(businessId)
        if (metadataDao.get(seededMarkerKey(businessId)) != null) return

        val legacy = parseLegacy(metadataDao.get(legacyKey(businessId))?.value)
        val fromPeople = counterpartyDao.getDistinctTypes(businessId)
        val wanted = (BUILT_IN_TYPES + legacy + fromPeople).mapNotNull { normalize(it) }.distinct()
        wanted.forEach { importSeedType(businessId, it) }

        metadataDao.upsert(
            LocalAppMetadataEntity(
                key = seededMarkerKey(businessId),
                value = "1",
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Create a seed/import row with a deterministic id so it dedupes against
     * the backend's own seed/backfill. Left PENDING_UPLOAD for the SyncWorker
     * to drain (no inline network — keeps first-run cheap and offline-safe). */
    private suspend fun importSeedType(businessId: String, normalized: String) {
        if (localDao.getActiveByNormalizedName(businessId, normalized).isNotEmpty()) return
        val id = seedTypeId(businessId, normalized)
        if (localDao.getByIdIncludingDeleted(id) != null) return
        val now = System.currentTimeMillis()
        localDao.upsert(
            LocalCounterpartyTypeEntity(
                id = id,
                businessId = businessId,
                name = normalized,
                normalizedName = normalized,
                isArchived = false,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                syncStatus = SyncStatus.PENDING_UPLOAD,
                lastSyncAttemptAt = now,
            )
        )
    }

    /** Add a user-typed person type. New rows get a random id (so a type the
     * user removed can be added back later — a deterministic id would 404
     * against the backend's soft-deleted row). Dedupes by normalized key. */
    suspend fun addType(businessId: String, rawType: String) {
        val normalized = normalize(rawType) ?: return
        if (localDao.getActiveByNormalizedName(businessId, normalized).isNotEmpty()) return
        val now = System.currentTimeMillis()
        val local = LocalCounterpartyTypeEntity(
            id = UUID.randomUUID().toString(),
            businessId = businessId,
            name = normalized,
            normalizedName = normalized,
            isArchived = false,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            syncStatus = SyncStatus.PENDING_UPLOAD,
            lastSyncAttemptAt = now,
        )
        localDao.upsert(local)
        runCatching { api.createCounterpartyType(businessId, local.toCreateDto()) }
            .onSuccess { remote ->
                localDao.upsert(remote.toEntity(SyncStatus.SYNCED))
                logSync(local, "create", "success")
            }
            .onFailure { t ->
                localDao.upsert(local.toFailed(t))
                logSync(local, "create", "failure", t)
            }
    }

    /** Remove a type from the picker by its snake_case key. Soft-deletes the
     * matching row(s); a never-synced row is dropped locally. People already
     * saved with the type keep it (it's a string snapshot on their row). */
    suspend fun removeType(businessId: String, type: String) {
        val normalized = normalize(type) ?: return
        localDao.getActiveByNormalizedName(businessId, normalized).forEach { deleteRow(it) }
    }

    private suspend fun deleteRow(current: LocalCounterpartyTypeEntity) {
        if (current.syncStatus == SyncStatus.PENDING_UPLOAD) {
            localDao.deleteById(current.id)
            return
        }
        val now = System.currentTimeMillis()
        val pending = current.copy(
            deletedAt = now,
            syncStatus = SyncStatus.PENDING_DELETE,
            lastSyncAttemptAt = now,
            lastSyncError = null,
        )
        localDao.upsert(pending)
        runCatching { api.deleteCounterpartyType(current.businessId, current.id) }
            .onSuccess {
                localDao.upsert(pending.copy(updatedAt = now, syncStatus = SyncStatus.SYNCED))
                logSync(pending, "delete", "success")
            }
            .onFailure { t ->
                if ((t as? HttpException)?.code() == 404) {
                    localDao.upsert(pending.copy(updatedAt = now, syncStatus = SyncStatus.SYNCED))
                    logSync(pending, "delete", "success")
                } else {
                    localDao.upsert(pending.toFailed(t))
                    logSync(pending, "delete", "failure", t)
                }
            }
    }

    suspend fun refreshFromRemote(businessId: String) {
        val remote = runCatching { api.listCounterpartyTypes(businessId) }.getOrNull() ?: return
        localDao.upsertAll(remote.map { it.toEntity(SyncStatus.SYNCED) })
    }

    suspend fun syncPending(
        businessId: String,
        includeAuthExpiredFailures: Boolean = false,
    ) {
        val rows = localDao.getRowsNeedingSync(businessId).filterForRetry(includeAuthExpiredFailures)
        rows.forEach { local ->
            when {
                local.deletedAt != null || local.syncStatus == SyncStatus.PENDING_DELETE ->
                    syncDelete(local)
                local.syncStatus == SyncStatus.PENDING_UPDATE -> syncUpdate(local)
                local.syncStatus == SyncStatus.FAILED -> syncCreateOrUpdate(businessId, local)
                else -> syncCreate(businessId, local)
            }
        }
    }

    private suspend fun syncCreate(businessId: String, local: LocalCounterpartyTypeEntity) {
        runCatching { api.createCounterpartyType(businessId, local.toCreateDto()) }
            .onSuccess { remote ->
                localDao.upsert(remote.toEntity(SyncStatus.SYNCED))
                logSync(local, "create", "success")
            }
            .onFailure { t ->
                localDao.upsert(local.toFailed(t))
                logSync(local, "create", "failure", t)
            }
    }

    private suspend fun syncUpdate(local: LocalCounterpartyTypeEntity) {
        runCatching { api.updateCounterpartyType(local.businessId, local.id, local.toUpdateDto()) }
            .onSuccess { remote ->
                localDao.upsert(remote.toEntity(SyncStatus.SYNCED))
                logSync(local, "update", "success")
            }
            .onFailure { t ->
                localDao.upsert(local.toFailed(t))
                logSync(local, "update", "failure", t)
            }
    }

    private suspend fun syncCreateOrUpdate(businessId: String, local: LocalCounterpartyTypeEntity) {
        if (local.updatedAt > local.createdAt) {
            val update = runCatching {
                api.updateCounterpartyType(local.businessId, local.id, local.toUpdateDto())
            }
            update
                .onSuccess { remote ->
                    localDao.upsert(remote.toEntity(SyncStatus.SYNCED))
                    logSync(local, "update", "success")
                    return
                }
                .onFailure { t ->
                    if ((t as? HttpException)?.code() != 404) {
                        localDao.upsert(local.toFailed(t))
                        logSync(local, "update", "failure", t)
                        return
                    }
                }
        }
        syncCreate(businessId, local)
    }

    private suspend fun syncDelete(local: LocalCounterpartyTypeEntity) {
        runCatching { api.deleteCounterpartyType(local.businessId, local.id) }
            .onSuccess {
                localDao.upsert(
                    local.copy(
                        syncStatus = SyncStatus.SYNCED,
                        lastSyncAttemptAt = System.currentTimeMillis(),
                        lastSyncError = null,
                    )
                )
                logSync(local, "delete", "success")
            }
            .onFailure { t ->
                if ((t as? HttpException)?.code() == 404) {
                    localDao.upsert(
                        local.copy(
                            syncStatus = SyncStatus.SYNCED,
                            lastSyncAttemptAt = System.currentTimeMillis(),
                            lastSyncError = null,
                        )
                    )
                    logSync(local, "delete", "success")
                } else {
                    localDao.upsert(local.toFailed(t))
                    logSync(local, "delete", "failure", t)
                }
            }
    }

    suspend fun clearLocal() {
        localDao.clear()
    }

    private fun LocalCounterpartyTypeEntity.toFailed(t: Throwable): LocalCounterpartyTypeEntity =
        copy(
            syncStatus = if (t.isSyncConflictFailure()) SyncStatus.CONFLICT else SyncStatus.FAILED,
            lastSyncAttemptAt = System.currentTimeMillis(),
            lastSyncError = t.toSyncErrorMessage(),
        )

    private fun List<LocalCounterpartyTypeEntity>.filterForRetry(
        includeAuthExpiredFailures: Boolean,
    ): List<LocalCounterpartyTypeEntity> =
        if (includeAuthExpiredFailures) {
            this
        } else {
            filterNot {
                it.syncStatus == SyncStatus.FAILED &&
                    it.lastSyncError == AUTH_EXPIRED_SYNC_MESSAGE
            }
        }

    private fun CounterpartyTypeDto.toEntity(syncStatus: SyncStatus): LocalCounterpartyTypeEntity {
        val now = System.currentTimeMillis()
        return LocalCounterpartyTypeEntity(
            id = id,
            businessId = businessId,
            name = name,
            normalizedName = normalizedName,
            isArchived = isArchived,
            createdAt = createdAt.toEpochMillisOrNull() ?: now,
            updatedAt = updatedAt.toEpochMillisOrNull() ?: now,
            deletedAt = deletedAt?.toEpochMillisOrNull(),
            syncStatus = syncStatus,
            lastSyncAttemptAt = now,
            remoteUpdatedAt = updatedAt,
        )
    }

    private fun LocalCounterpartyTypeEntity.toCreateDto(): CounterpartyTypeCreateDto =
        CounterpartyTypeCreateDto(id = id, name = name)

    private fun LocalCounterpartyTypeEntity.toUpdateDto(): CounterpartyTypeUpdateDto =
        CounterpartyTypeUpdateDto(
            name = name,
            isArchived = isArchived,
            expectedUpdatedAt = remoteUpdatedAt,
        )

    private fun String.toEpochMillisOrNull(): Long? =
        runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(this).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()

    /** Normalize to the snake_case key form used everywhere a `type` is stored.
     * Must stay byte-for-byte identical to the backend's
     * `normalize_counterparty_type_name` so the deterministic seed ids match. */
    private fun normalize(value: String): String? =
        value.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { null }

    private fun parseLegacy(value: String?): List<String> =
        value?.split("\n")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

    private fun legacyKey(businessId: String) = "$LEGACY_PREFIX$businessId"

    private fun seededMarkerKey(businessId: String) = "$SEEDED_PREFIX$businessId"

    private suspend fun logSync(
        row: LocalCounterpartyTypeEntity,
        action: String,
        status: String,
        throwable: Throwable? = null,
    ) {
        syncEventLogger.log(
            businessId = row.businessId,
            entityType = "counterparty_type",
            entityId = row.id,
            action = action,
            status = status,
            errorMessage = throwable?.toSyncErrorMessage(),
        )
    }

    companion object {
        /** Legacy on-device metadata key (pre-sync). Read once during the
         * one-time import in [seedIfNeeded], then never written again. */
        private const val LEGACY_PREFIX = "counterparty_types:"

        /** Marks that this device has run its one-time seed/import for a
         * business, so [seedIfNeeded] doesn't re-seed removed types. */
        private const val SEEDED_PREFIX = "counterparty_types_seeded:"

        val BUILT_IN_TYPES = listOf("customer", "supplier", "subcontractor", "employee")

        /** Must match the backend `_SEED_NAMESPACE` in counterparty_type.py. */
        private val SEED_NAMESPACE = UUID.fromString("7c9e6a41-3b2d-5f48-9a1c-8d6e0f2b4a73")

        /** RFC 4122 v5 (SHA-1) UUID, matching Python's `uuid.uuid5`, so an
         * imported/seeded type carries the same id the backend assigns. */
        internal fun seedTypeId(businessId: String, normalizedName: String): String =
            uuid5(SEED_NAMESPACE, "$businessId:$normalizedName")

        private fun uuid5(namespace: UUID, name: String): String {
            val nsBytes = ByteArray(16)
            val msb = namespace.mostSignificantBits
            val lsb = namespace.leastSignificantBits
            for (i in 0 until 8) nsBytes[i] = (msb ushr (8 * (7 - i))).toByte()
            for (i in 0 until 8) nsBytes[8 + i] = (lsb ushr (8 * (7 - i))).toByte()
            val digest = MessageDigest.getInstance("SHA-1").apply {
                update(nsBytes)
                update(name.toByteArray(Charsets.UTF_8))
            }.digest()
            val b = digest.copyOf(16)
            b[6] = ((b[6].toInt() and 0x0f) or 0x50).toByte() // version 5
            b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte() // RFC 4122 variant
            var outMsb = 0L
            var outLsb = 0L
            for (i in 0 until 8) outMsb = (outMsb shl 8) or (b[i].toLong() and 0xff)
            for (i in 8 until 16) outLsb = (outLsb shl 8) or (b[i].toLong() and 0xff)
            return UUID(outMsb, outLsb).toString()
        }
    }
}
