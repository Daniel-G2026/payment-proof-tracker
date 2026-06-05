package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalProjectDao
import com.danielanazodo.proofledger.data.local.entity.LocalProjectEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.ProjectCreateDto
import com.danielanazodo.proofledger.data.remote.dto.ProjectDto
import com.danielanazodo.proofledger.data.remote.dto.ProjectUpdateDto
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
 * Per-business list of projects (developments/estates), Phase 13 Cut 1. Same
 * offline-first sync machinery as [CategoryRepository] — client-id-keyed CRUD,
 * dependency-ordered drain via [SyncWorker] — minus the archive flag. Units
 * hang off a project and must sync after it (see SyncWorker drain order).
 */
@Singleton
class ProjectRepository @Inject constructor(
    private val api: ApiService,
    private val localDao: LocalProjectDao,
    private val syncEventLogger: SyncEventLogger,
) {
    fun observeByBusiness(businessId: String): Flow<List<LocalProjectEntity>> =
        localDao.observeByBusiness(businessId)

    suspend fun getById(projectId: String): LocalProjectEntity? =
        localDao.getById(projectId)

    suspend fun refreshFromRemote(businessId: String) {
        val remote = runCatching { api.listProjects(businessId) }.getOrNull() ?: return
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

    private suspend fun syncCreate(businessId: String, local: LocalProjectEntity) {
        runCatching { api.createProject(businessId, local.toCreateDto()) }
            .onSuccess { remote ->
                localDao.upsert(remote.toEntity(SyncStatus.SYNCED))
                logSync(local, "create", "success")
            }
            .onFailure { t ->
                localDao.upsert(local.toFailed(t))
                logSync(local, "create", "failure", t)
            }
    }

    private suspend fun syncUpdate(local: LocalProjectEntity) {
        runCatching { api.updateProject(local.businessId, local.id, local.toUpdateDto()) }
            .onSuccess { remote ->
                localDao.upsert(remote.toEntity(SyncStatus.SYNCED))
                logSync(local, "update", "success")
            }
            .onFailure { t ->
                localDao.upsert(local.toFailed(t))
                logSync(local, "update", "failure", t)
            }
    }

    private suspend fun syncCreateOrUpdate(businessId: String, local: LocalProjectEntity) {
        if (local.updatedAt > local.createdAt) {
            val update = runCatching { api.updateProject(local.businessId, local.id, local.toUpdateDto()) }
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

    private suspend fun syncDelete(local: LocalProjectEntity) {
        runCatching { api.deleteProject(local.businessId, local.id) }
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

    private suspend fun markDeleteSynced(local: LocalProjectEntity) {
        localDao.upsert(
            local.copy(
                syncStatus = SyncStatus.SYNCED,
                lastSyncAttemptAt = System.currentTimeMillis(),
                lastSyncError = null,
            )
        )
        logSync(local, "delete", "success")
    }

    private fun LocalProjectEntity.toFailed(t: Throwable): LocalProjectEntity =
        copy(
            syncStatus = if (t.isSyncConflictFailure()) SyncStatus.CONFLICT else SyncStatus.FAILED,
            lastSyncAttemptAt = System.currentTimeMillis(),
            lastSyncError = t.toSyncErrorMessage(),
        )

    private fun List<LocalProjectEntity>.filterForRetry(
        includeAuthExpiredFailures: Boolean,
    ): List<LocalProjectEntity> =
        if (includeAuthExpiredFailures) {
            this
        } else {
            filterNot {
                it.syncStatus == SyncStatus.FAILED &&
                    it.lastSyncError == AUTH_EXPIRED_SYNC_MESSAGE
            }
        }

    suspend fun createProject(
        businessId: String,
        name: String,
        note: String? = null,
    ): LocalProjectEntity {
        val now = System.currentTimeMillis()
        val local = LocalProjectEntity(
            id = UUID.randomUUID().toString(),
            businessId = businessId,
            name = name.trim(),
            note = note?.trim()?.ifBlank { null },
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            syncStatus = SyncStatus.PENDING_UPLOAD,
            lastSyncAttemptAt = now,
        )
        localDao.upsert(local)
        return runCatching { api.createProject(businessId, local.toCreateDto()) }
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

    suspend fun deleteProject(projectId: String) {
        val current = localDao.getById(projectId) ?: return
        if (current.syncStatus == SyncStatus.PENDING_UPLOAD) {
            localDao.deleteById(projectId)
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
        runCatching { api.deleteProject(current.businessId, current.id) }
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

    private fun ProjectDto.toEntity(syncStatus: SyncStatus): LocalProjectEntity {
        val now = System.currentTimeMillis()
        return LocalProjectEntity(
            id = id,
            businessId = businessId,
            name = name,
            note = note,
            createdAt = createdAt.toEpochMillisOrNull() ?: now,
            updatedAt = updatedAt.toEpochMillisOrNull() ?: now,
            deletedAt = deletedAt?.toEpochMillisOrNull(),
            syncStatus = syncStatus,
            lastSyncAttemptAt = now,
            remoteUpdatedAt = updatedAt,
        )
    }

    private fun LocalProjectEntity.toCreateDto(): ProjectCreateDto =
        ProjectCreateDto(id = id, name = name, note = note)

    private fun LocalProjectEntity.toUpdateDto(): ProjectUpdateDto =
        ProjectUpdateDto(name = name, note = note, expectedUpdatedAt = remoteUpdatedAt)

    private fun String.toEpochMillisOrNull(): Long? =
        runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(this).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()

    private suspend fun logSync(
        row: LocalProjectEntity,
        action: String,
        status: String,
        throwable: Throwable? = null,
    ) {
        syncEventLogger.log(
            businessId = row.businessId,
            entityType = "project",
            entityId = row.id,
            action = action,
            status = status,
            errorMessage = throwable?.toSyncErrorMessage(),
        )
    }
}
