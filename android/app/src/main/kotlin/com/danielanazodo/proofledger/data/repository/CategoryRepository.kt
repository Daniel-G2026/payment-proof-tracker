package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalCategoryDao
import com.danielanazodo.proofledger.data.local.entity.LocalCategoryEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.CategoryCreateDto
import com.danielanazodo.proofledger.data.remote.dto.CategoryDto
import com.danielanazodo.proofledger.data.remote.dto.CategoryUpdateDto
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
 * Per-business category list backing the structured-category picker. A lighter
 * sibling of [CounterpartyRepository] — same offline-first sync machinery,
 * minus type/phone/notes, plus an archive flag. The transaction itself keeps
 * storing the chosen category as a plain string snapshot, so this repository
 * only feeds the picker and "add your own".
 */
@Singleton
class CategoryRepository @Inject constructor(
    private val api: ApiService,
    private val localDao: LocalCategoryDao,
    private val syncEventLogger: SyncEventLogger,
) {
    fun observeByBusiness(businessId: String): Flow<List<LocalCategoryEntity>> =
        localDao.observeByBusiness(businessId)

    suspend fun getById(categoryId: String): LocalCategoryEntity? =
        localDao.getById(categoryId)

    suspend fun refreshFromRemote(businessId: String) {
        val remote = runCatching { api.listCategories(businessId) }.getOrNull() ?: return
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

    private suspend fun syncCreate(businessId: String, local: LocalCategoryEntity) {
        runCatching { api.createCategory(businessId, local.toCreateDto()) }
            .onSuccess { remote ->
                localDao.upsert(remote.toEntity(SyncStatus.SYNCED))
                logSync(local, "create", "success")
            }
            .onFailure { t ->
                localDao.upsert(local.toFailed(t))
                logSync(local, "create", "failure", t)
            }
    }

    private suspend fun syncUpdate(local: LocalCategoryEntity) {
        runCatching { api.updateCategory(local.businessId, local.id, local.toUpdateDto()) }
            .onSuccess { remote ->
                localDao.upsert(remote.toEntity(SyncStatus.SYNCED))
                logSync(local, "update", "success")
            }
            .onFailure { t ->
                localDao.upsert(local.toFailed(t))
                logSync(local, "update", "failure", t)
            }
    }

    private suspend fun syncCreateOrUpdate(businessId: String, local: LocalCategoryEntity) {
        if (local.updatedAt > local.createdAt) {
            val update = runCatching { api.updateCategory(local.businessId, local.id, local.toUpdateDto()) }
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

    private suspend fun syncDelete(local: LocalCategoryEntity) {
        runCatching { api.deleteCategory(local.businessId, local.id) }
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

    private fun LocalCategoryEntity.toFailed(t: Throwable): LocalCategoryEntity =
        copy(
            syncStatus = if (t.isSyncConflictFailure()) SyncStatus.CONFLICT else SyncStatus.FAILED,
            lastSyncAttemptAt = System.currentTimeMillis(),
            lastSyncError = t.toSyncErrorMessage(),
        )

    private fun List<LocalCategoryEntity>.filterForRetry(
        includeAuthExpiredFailures: Boolean,
    ): List<LocalCategoryEntity> =
        if (includeAuthExpiredFailures) {
            this
        } else {
            filterNot {
                it.syncStatus == SyncStatus.FAILED &&
                    it.lastSyncError == AUTH_EXPIRED_SYNC_MESSAGE
            }
        }

    suspend fun createCategory(
        businessId: String,
        name: String,
    ): LocalCategoryEntity {
        val now = System.currentTimeMillis()
        val local = LocalCategoryEntity(
            id = UUID.randomUUID().toString(),
            businessId = businessId,
            name = name.trim(),
            normalizedName = name.normalizedName(),
            isArchived = false,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            syncStatus = SyncStatus.PENDING_UPLOAD,
            lastSyncAttemptAt = now,
        )
        localDao.upsert(local)
        return runCatching { api.createCategory(businessId, local.toCreateDto()) }
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

    suspend fun clearLocal() {
        localDao.clear()
    }

    private fun CategoryDto.toEntity(syncStatus: SyncStatus): LocalCategoryEntity {
        val now = System.currentTimeMillis()
        return LocalCategoryEntity(
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

    private fun LocalCategoryEntity.toCreateDto(): CategoryCreateDto =
        CategoryCreateDto(
            id = id,
            name = name,
        )

    private fun LocalCategoryEntity.toUpdateDto(): CategoryUpdateDto =
        CategoryUpdateDto(
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

    private fun String.normalizedName(): String = trim().lowercase().replace(Regex("\\s+"), " ")

    private suspend fun logSync(
        row: LocalCategoryEntity,
        action: String,
        status: String,
        throwable: Throwable? = null,
    ) {
        syncEventLogger.log(
            businessId = row.businessId,
            entityType = "category",
            entityId = row.id,
            action = action,
            status = status,
            errorMessage = throwable?.toSyncErrorMessage(),
        )
    }
}
