package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.dao.LocalBusinessDao
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity
import com.danielanazodo.proofledger.data.local.entity.LocalBusinessEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.BusinessCreateDto
import com.danielanazodo.proofledger.data.remote.dto.BusinessDto
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BusinessRepository @Inject constructor(
    private val api: ApiService,
    private val businessDao: LocalBusinessDao,
    private val metadataDao: LocalAppMetadataDao,
) {

    suspend fun getLocalBusinesses(): List<LocalBusinessEntity> = businessDao.getAll()

    suspend fun countLocalBusinesses(): Int = businessDao.count()

    /** Pull authoritative list from backend and cache locally. Best-effort. */
    suspend fun refreshFromRemote(): List<LocalBusinessEntity> {
        val remote = runCatching { api.listBusinesses() }.getOrNull() ?: return businessDao.getAll()
        val now = System.currentTimeMillis()
        businessDao.upsertAll(remote.map { it.toEntity(now, SyncStatus.SYNCED) })
        return businessDao.getAll()
    }

    /**
     * Local-first create. Client generates the UUID, saves locally as
     * PENDING_UPLOAD, then POSTs. Backend treats POST as upsert-by-id so retries
     * are idempotent.
     */
    suspend fun createBusiness(
        ownerId: String,
        name: String,
        businessType: String?,
    ): LocalBusinessEntity {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val local = LocalBusinessEntity(
            id = id,
            ownerId = ownerId,
            name = name,
            country = "Nigeria",
            currency = "NGN",
            businessType = businessType,
            plan = "free",
            createdAt = now,
            updatedAt = now,
            syncStatus = SyncStatus.PENDING_UPLOAD,
            lastSyncAttemptAt = now,
        )
        businessDao.upsert(local)

        val remote = runCatching {
            api.createBusiness(
                BusinessCreateDto(
                    id = id,
                    name = name,
                    businessType = businessType,
                )
            )
        }.getOrNull()

        return if (remote != null) {
            val synced = remote.toEntity(System.currentTimeMillis(), SyncStatus.SYNCED)
            businessDao.upsert(synced)
            synced
        } else {
            local
        }
    }

    suspend fun selectBusiness(businessId: String) {
        metadataDao.upsert(
            LocalAppMetadataEntity(
                key = KEY_SELECTED_BUSINESS,
                value = businessId,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun getSelectedBusinessId(): String? =
        metadataDao.get(KEY_SELECTED_BUSINESS)?.value

    suspend fun getSelectedBusiness(): LocalBusinessEntity? {
        val id = getSelectedBusinessId() ?: return null
        return businessDao.getById(id)
    }

    suspend fun clearSelectedBusiness() {
        metadataDao.upsert(
            LocalAppMetadataEntity(
                key = KEY_SELECTED_BUSINESS,
                value = "",
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Wipes all cached businesses + selected-business pointer. Call on sign-out
     *  or user-id mismatch so the next user starts from a clean slate. */
    suspend fun clearAllLocal() {
        businessDao.clear()
        clearSelectedBusiness()
    }

    private fun BusinessDto.toEntity(now: Long, status: SyncStatus): LocalBusinessEntity =
        LocalBusinessEntity(
            id = id,
            ownerId = ownerId,
            name = name,
            country = country,
            currency = currency,
            businessType = businessType,
            plan = plan,
            createdAt = now,
            updatedAt = now,
            syncStatus = status,
            lastSyncAttemptAt = now,
        )

    private companion object {
        const val KEY_SELECTED_BUSINESS = "selected_business_id"
    }
}
