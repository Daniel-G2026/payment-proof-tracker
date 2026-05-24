package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalCounterpartyDao
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyCreateDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyUpdateDto
import com.danielanazodo.proofledger.data.sync.toSyncErrorMessage
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class CounterpartyRepository @Inject constructor(
    private val api: ApiService,
    private val localDao: LocalCounterpartyDao,
) {
    fun observeByBusiness(businessId: String): Flow<List<LocalCounterpartyEntity>> =
        localDao.observeByBusiness(businessId)

    fun observeFiltered(
        businessId: String,
        search: String?,
        type: String?,
    ): Flow<List<LocalCounterpartyEntity>> =
        localDao.observeFiltered(businessId, search?.trim()?.lowercase(), type)

    fun observeById(counterpartyId: String): Flow<LocalCounterpartyEntity?> =
        localDao.observeById(counterpartyId)

    suspend fun getById(counterpartyId: String): LocalCounterpartyEntity? =
        localDao.getById(counterpartyId)

    suspend fun refreshFromRemote(businessId: String) {
        val remote = runCatching { api.listCounterparties(businessId) }.getOrNull() ?: return
        localDao.upsertAll(remote.map { it.toEntity(SyncStatus.SYNCED) })
    }

    /** Back-compat alias — sync worker now uses [syncPending]. */
    suspend fun syncPendingUploads(businessId: String) = syncPending(businessId)

    suspend fun syncPending(businessId: String) {
        val rows = localDao.getRowsNeedingSync(businessId)
        rows.forEach { local ->
            when {
                local.deletedAt != null || local.syncStatus == SyncStatus.PENDING_DELETE ->
                    syncDelete(local)
                local.syncStatus == SyncStatus.PENDING_UPDATE -> syncUpdate(local)
                else -> syncCreate(businessId, local)
            }
        }
    }

    private suspend fun syncCreate(businessId: String, local: LocalCounterpartyEntity) {
        runCatching { api.createCounterparty(businessId, local.toCreateDto()) }
            .onSuccess { remote -> localDao.upsert(remote.toEntity(SyncStatus.SYNCED)) }
            .onFailure { t -> localDao.upsert(local.toFailed(t)) }
    }

    private suspend fun syncUpdate(local: LocalCounterpartyEntity) {
        runCatching { api.updateCounterparty(local.businessId, local.id, local.toUpdateDto()) }
            .onSuccess { remote -> localDao.upsert(remote.toEntity(SyncStatus.SYNCED)) }
            .onFailure { t -> localDao.upsert(local.toFailed(t)) }
    }

    private suspend fun syncDelete(local: LocalCounterpartyEntity) {
        runCatching { api.deleteCounterparty(local.businessId, local.id) }
            .onSuccess {
                localDao.upsert(
                    local.copy(
                        syncStatus = SyncStatus.SYNCED,
                        lastSyncAttemptAt = System.currentTimeMillis(),
                        lastSyncError = null,
                    )
                )
            }
            .onFailure { t -> localDao.upsert(local.toFailed(t)) }
    }

    private fun LocalCounterpartyEntity.toFailed(t: Throwable): LocalCounterpartyEntity =
        copy(
            syncStatus = SyncStatus.FAILED,
            lastSyncAttemptAt = System.currentTimeMillis(),
            lastSyncError = t.toSyncErrorMessage(),
        )

    suspend fun createCounterparty(
        businessId: String,
        name: String,
        type: String,
        phone: String?,
        notes: String?,
    ): LocalCounterpartyEntity {
        val now = System.currentTimeMillis()
        val local = LocalCounterpartyEntity(
            id = UUID.randomUUID().toString(),
            businessId = businessId,
            name = name.trim(),
            normalizedName = name.normalizedName(),
            type = type,
            phone = phone.clean(),
            notes = notes.clean(),
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            syncStatus = SyncStatus.PENDING_UPLOAD,
            lastSyncAttemptAt = now,
        )
        localDao.upsert(local)
        return runCatching { api.createCounterparty(businessId, local.toCreateDto()) }
            .fold(
                onSuccess = { remote ->
                    val synced = remote.toEntity(SyncStatus.SYNCED)
                    localDao.upsert(synced)
                    synced
                },
                onFailure = { t ->
                    val failed = local.toFailed(t)
                    localDao.upsert(failed)
                    failed
                },
            )
    }

    suspend fun updateCounterparty(
        counterpartyId: String,
        name: String,
        type: String,
        phone: String?,
        notes: String?,
    ): LocalCounterpartyEntity {
        val current = localDao.getById(counterpartyId)
            ?: throw IllegalStateException("Counterparty not found.")
        val now = System.currentTimeMillis()
        val updated = current.copy(
            name = name.trim(),
            normalizedName = name.normalizedName(),
            type = type,
            phone = phone.clean(),
            notes = notes.clean(),
            updatedAt = now,
            syncStatus = SyncStatus.PENDING_UPDATE,
            lastSyncAttemptAt = now,
        )
        localDao.upsert(updated)
        return runCatching {
            api.updateCounterparty(current.businessId, current.id, updated.toUpdateDto())
        }.fold(
            onSuccess = { remote ->
                val synced = remote.toEntity(SyncStatus.SYNCED)
                localDao.upsert(synced)
                synced
            },
            onFailure = { t ->
                val failed = updated.toFailed(t)
                localDao.upsert(failed)
                failed
            },
        )
    }

    suspend fun deleteCounterparty(counterpartyId: String) {
        val current = localDao.getById(counterpartyId)
            ?: throw IllegalStateException("Counterparty not found.")
        val now = System.currentTimeMillis()
        val pending = current.copy(
            deletedAt = now,
            syncStatus = SyncStatus.PENDING_DELETE,
            lastSyncAttemptAt = now,
            lastSyncError = null,
        )
        localDao.upsert(pending)
        runCatching { api.deleteCounterparty(current.businessId, current.id) }
            .onSuccess {
                localDao.upsert(pending.copy(updatedAt = now, syncStatus = SyncStatus.SYNCED))
            }
            .onFailure { t -> localDao.upsert(pending.toFailed(t)) }
    }

    suspend fun clearLocal() {
        localDao.clear()
    }

    private fun CounterpartyDto.toEntity(syncStatus: SyncStatus): LocalCounterpartyEntity {
        val now = System.currentTimeMillis()
        return LocalCounterpartyEntity(
            id = id,
            businessId = businessId,
            name = name,
            normalizedName = normalizedName,
            type = type,
            phone = phone,
            notes = notes,
            createdAt = createdAt.toEpochMillisOrNull() ?: now,
            updatedAt = updatedAt.toEpochMillisOrNull() ?: now,
            deletedAt = deletedAt?.toEpochMillisOrNull(),
            syncStatus = syncStatus,
            lastSyncAttemptAt = now,
        )
    }

    private fun LocalCounterpartyEntity.toCreateDto(): CounterpartyCreateDto =
        CounterpartyCreateDto(
            id = id,
            name = name,
            type = type,
            phone = phone,
            notes = notes,
        )

    private fun LocalCounterpartyEntity.toUpdateDto(): CounterpartyUpdateDto =
        CounterpartyUpdateDto(
            name = name,
            type = type,
            phone = phone,
            notes = notes,
        )

    private fun String.toEpochMillisOrNull(): Long? =
        runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(this).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()

    private fun String.normalizedName(): String = trim().lowercase().replace(Regex("\\s+"), " ")

    private fun String?.clean(): String? = this?.trim()?.ifBlank { null }
}
