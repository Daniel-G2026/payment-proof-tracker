package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalCounterpartyDao
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyCreateDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyUpdateDto
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

    suspend fun syncPendingUploads(businessId: String) {
        val pending = localDao.getByBusinessAndSyncStatus(
            businessId = businessId,
            syncStatus = SyncStatus.PENDING_UPLOAD.name,
        )
        pending.forEach { local ->
            val remote = runCatching {
                api.createCounterparty(businessId, local.toCreateDto())
            }.getOrNull()
            if (remote != null) {
                localDao.upsert(remote.toEntity(SyncStatus.SYNCED))
            }
        }
    }

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
        val remote = runCatching {
            api.createCounterparty(businessId, local.toCreateDto())
        }.getOrNull()
        return if (remote != null) {
            val synced = remote.toEntity(SyncStatus.SYNCED)
            localDao.upsert(synced)
            synced
        } else {
            local
        }
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
        val remote = runCatching {
            api.updateCounterparty(current.businessId, current.id, updated.toUpdateDto())
        }.getOrNull()
        return if (remote != null) {
            val synced = remote.toEntity(SyncStatus.SYNCED)
            localDao.upsert(synced)
            synced
        } else {
            updated
        }
    }

    suspend fun deleteCounterparty(counterpartyId: String) {
        val current = localDao.getById(counterpartyId)
            ?: throw IllegalStateException("Counterparty not found.")
        val now = System.currentTimeMillis()
        localDao.upsert(
            current.copy(
                deletedAt = now,
                syncStatus = SyncStatus.PENDING_DELETE,
                lastSyncAttemptAt = now,
            )
        )
        val deletedRemote = runCatching {
            api.deleteCounterparty(current.businessId, current.id)
        }.isSuccess
        if (deletedRemote) {
            localDao.upsert(
                current.copy(
                    deletedAt = now,
                    updatedAt = now,
                    syncStatus = SyncStatus.SYNCED,
                    lastSyncAttemptAt = now,
                )
            )
        }
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
