package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalUnitDao
import com.danielanazodo.proofledger.data.local.entity.LocalUnitEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.UnitCreateDto
import com.danielanazodo.proofledger.data.remote.dto.UnitDto
import com.danielanazodo.proofledger.data.remote.dto.UnitUpdateDto
import com.danielanazodo.proofledger.data.sync.AUTH_EXPIRED_SYNC_MESSAGE
import com.danielanazodo.proofledger.data.sync.SyncEventLogger
import com.danielanazodo.proofledger.data.sync.isSyncConflictFailure
import com.danielanazodo.proofledger.data.sync.toSyncErrorMessage
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException

/**
 * Per-business list of units (houses/plots), Phase 13 Cut 1. Same offline-first
 * sync machinery as [ProjectRepository]. A unit points at a project (its parent
 * — must sync first) and optionally at a buyer counterparty. The active
 * versioned schedule arrives in Cut 2.
 */
@Singleton
class UnitRepository @Inject constructor(
    private val api: ApiService,
    private val localDao: LocalUnitDao,
    private val syncEventLogger: SyncEventLogger,
) {
    fun observeByBusiness(businessId: String): Flow<List<LocalUnitEntity>> =
        localDao.observeByBusiness(businessId)

    fun observeByProject(projectId: String): Flow<List<LocalUnitEntity>> =
        localDao.observeByProject(projectId)

    suspend fun getById(unitId: String): LocalUnitEntity? =
        localDao.getById(unitId)

    suspend fun refreshFromRemote(businessId: String) {
        val remote = runCatching { api.listUnits(businessId) }.getOrNull() ?: return
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

    private suspend fun syncCreate(businessId: String, local: LocalUnitEntity) {
        runCatching { api.createUnit(businessId, local.toCreateDto()) }
            .onSuccess { remote ->
                localDao.upsert(remote.toEntity(SyncStatus.SYNCED))
                logSync(local, "create", "success")
            }
            .onFailure { t ->
                localDao.upsert(local.toFailed(t))
                logSync(local, "create", "failure", t)
            }
    }

    private suspend fun syncUpdate(local: LocalUnitEntity) {
        runCatching { api.updateUnit(local.businessId, local.id, local.toUpdateDto()) }
            .onSuccess { remote ->
                localDao.upsert(remote.toEntity(SyncStatus.SYNCED))
                logSync(local, "update", "success")
            }
            .onFailure { t ->
                localDao.upsert(local.toFailed(t))
                logSync(local, "update", "failure", t)
            }
    }

    private suspend fun syncCreateOrUpdate(businessId: String, local: LocalUnitEntity) {
        if (local.updatedAt > local.createdAt) {
            val update = runCatching { api.updateUnit(local.businessId, local.id, local.toUpdateDto()) }
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

    private suspend fun syncDelete(local: LocalUnitEntity) {
        runCatching { api.deleteUnit(local.businessId, local.id) }
            .onSuccess { markDeleteSynced(local) }
            .onFailure { t ->
                if ((t as? HttpException)?.code() == 404) {
                    markDeleteSynced(local)
                } else {
                    localDao.upsert(local.toFailed(t))
                    logSync(local, "delete", "failure", t)
                }
            }
    }

    private suspend fun markDeleteSynced(local: LocalUnitEntity) {
        localDao.upsert(
            local.copy(
                syncStatus = SyncStatus.SYNCED,
                lastSyncAttemptAt = System.currentTimeMillis(),
                lastSyncError = null,
            )
        )
        logSync(local, "delete", "success")
    }

    private fun LocalUnitEntity.toFailed(t: Throwable): LocalUnitEntity =
        copy(
            syncStatus = if (t.isSyncConflictFailure()) SyncStatus.CONFLICT else SyncStatus.FAILED,
            lastSyncAttemptAt = System.currentTimeMillis(),
            lastSyncError = t.toSyncErrorMessage(),
        )

    private fun List<LocalUnitEntity>.filterForRetry(
        includeAuthExpiredFailures: Boolean,
    ): List<LocalUnitEntity> =
        if (includeAuthExpiredFailures) {
            this
        } else {
            filterNot {
                it.syncStatus == SyncStatus.FAILED &&
                    it.lastSyncError == AUTH_EXPIRED_SYNC_MESSAGE
            }
        }

    suspend fun createUnit(
        businessId: String,
        projectId: String,
        label: String,
        buyerCounterpartyId: String? = null,
        totalPriceMinor: Long? = null,
        currency: String = "NGN",
        status: String = "available",
    ): LocalUnitEntity {
        val now = System.currentTimeMillis()
        val local = LocalUnitEntity(
            id = UUID.randomUUID().toString(),
            businessId = businessId,
            projectId = projectId,
            label = label.trim(),
            buyerCounterpartyId = buyerCounterpartyId,
            totalPriceMinor = totalPriceMinor,
            currency = currency,
            status = status,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            syncStatus = SyncStatus.PENDING_UPLOAD,
            lastSyncAttemptAt = now,
        )
        localDao.upsert(local)
        return runCatching { api.createUnit(businessId, local.toCreateDto()) }
            .fold(
                onSuccess = { remote ->
                    val synced = remote.toEntity(SyncStatus.SYNCED)
                    localDao.upsert(synced)
                    logSync(local, "create", "success")
                    synced
                },
                onFailure = { t ->
                    val failed = local.toFailed(t)
                    localDao.upsert(failed)
                    logSync(local, "create", "failure", t)
                    failed
                },
            )
    }

    /**
     * Edit a unit (label / buyer / price / status). Marks the row
     * PENDING_UPDATE and attempts the patch now; sync retry is the fallback.
     * Passing a field as null leaves it unchanged here except [buyerCounterpartyId]
     * and [totalPriceMinor], which are nullable values on the unit — use the
     * dedicated helpers below to clear them explicitly if needed.
     */
    suspend fun updateUnit(
        unitId: String,
        label: String? = null,
        buyerCounterpartyId: String? = null,
        totalPriceMinor: Long? = null,
        currency: String? = null,
        status: String? = null,
    ): LocalUnitEntity? {
        val current = localDao.getById(unitId) ?: return null
        val now = System.currentTimeMillis()
        val updated = current.copy(
            label = label?.trim() ?: current.label,
            buyerCounterpartyId = buyerCounterpartyId ?: current.buyerCounterpartyId,
            totalPriceMinor = totalPriceMinor ?: current.totalPriceMinor,
            currency = currency ?: current.currency,
            status = status ?: current.status,
            updatedAt = now,
            syncStatus = SyncStatus.PENDING_UPDATE,
            lastSyncAttemptAt = now,
            lastSyncError = null,
        )
        localDao.upsert(updated)
        return runCatching { api.updateUnit(updated.businessId, updated.id, updated.toUpdateDto()) }
            .fold(
                onSuccess = { remote ->
                    val synced = remote.toEntity(SyncStatus.SYNCED)
                    localDao.upsert(synced)
                    logSync(updated, "update", "success")
                    synced
                },
                onFailure = { t ->
                    val failed = updated.toFailed(t)
                    localDao.upsert(failed)
                    logSync(updated, "update", "failure", t)
                    failed
                },
            )
    }

    suspend fun deleteUnit(unitId: String) {
        val current = localDao.getById(unitId) ?: return
        if (current.syncStatus == SyncStatus.PENDING_UPLOAD) {
            localDao.deleteById(unitId)
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
        runCatching { api.deleteUnit(current.businessId, current.id) }
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

    suspend fun clearLocal() {
        localDao.clear()
    }

    private fun UnitDto.toEntity(syncStatus: SyncStatus): LocalUnitEntity {
        val now = System.currentTimeMillis()
        return LocalUnitEntity(
            id = id,
            businessId = businessId,
            projectId = projectId,
            label = label,
            buyerCounterpartyId = buyerCounterpartyId,
            totalPriceMinor = totalPriceMinor,
            currency = currency,
            status = status,
            createdAt = createdAt.toEpochMillisOrNull() ?: now,
            updatedAt = updatedAt.toEpochMillisOrNull() ?: now,
            deletedAt = deletedAt?.toEpochMillisOrNull(),
            syncStatus = syncStatus,
            lastSyncAttemptAt = now,
            remoteUpdatedAt = updatedAt,
        )
    }

    private fun LocalUnitEntity.toCreateDto(): UnitCreateDto =
        UnitCreateDto(
            id = id,
            projectId = projectId,
            label = label,
            buyerCounterpartyId = buyerCounterpartyId,
            totalPriceMinor = totalPriceMinor,
            currency = currency,
            status = status,
        )

    private fun LocalUnitEntity.toUpdateDto(): UnitUpdateDto =
        UnitUpdateDto(
            label = label,
            buyerCounterpartyId = buyerCounterpartyId,
            totalPriceMinor = totalPriceMinor,
            currency = currency,
            status = status,
            expectedUpdatedAt = remoteUpdatedAt,
        )

    private fun String.toEpochMillisOrNull(): Long? =
        runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(this).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()

    private suspend fun logSync(
        row: LocalUnitEntity,
        action: String,
        status: String,
        throwable: Throwable? = null,
    ) {
        syncEventLogger.log(
            businessId = row.businessId,
            entityType = "unit",
            entityId = row.id,
            action = action,
            status = status,
            errorMessage = throwable?.toSyncErrorMessage(),
        )
    }
}
