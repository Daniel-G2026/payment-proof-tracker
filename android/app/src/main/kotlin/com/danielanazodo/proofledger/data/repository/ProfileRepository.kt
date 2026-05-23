package com.danielanazodo.proofledger.data.repository

import android.util.Log
import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalProfileDao
import com.danielanazodo.proofledger.data.local.entity.LocalProfileEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.ProfileDto
import com.danielanazodo.proofledger.data.remote.dto.ProfileUpdateDto
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "proofledger.profile"

@Singleton
class ProfileRepository @Inject constructor(
    private val api: ApiService,
    private val localDao: LocalProfileDao,
) {
    /** Local-first: return cached profile if present, otherwise fetch + cache. */
    suspend fun getCurrentProfile(): LocalProfileEntity? {
        localDao.getFirst()?.let { return it }
        return runCatching { api.getMe() }.getOrNull()?.also { cache(it) }
            ?.let { localDao.getFirst() }
    }

    suspend fun refreshFromRemote(): LocalProfileEntity? {
        Log.d(TAG, "refreshFromRemote: calling GET /me")
        val result = runCatching { api.getMe() }
        val dto = result.getOrNull()
        if (dto == null) {
            Log.e(TAG, "refreshFromRemote: GET /me failed: ${result.exceptionOrNull()?.message}",
                result.exceptionOrNull())
            return null
        }
        Log.d(TAG, "refreshFromRemote: got profile id=${dto.id}")
        cache(dto)
        return localDao.getById(dto.id)
    }

    /** Save locally first, then attempt remote. Phase 10 wires retry on failure. */
    suspend fun updateProfile(fullName: String?, phone: String?): LocalProfileEntity? {
        val current = localDao.getFirst() ?: return null
        val now = System.currentTimeMillis()
        val updated = current.copy(
            fullName = fullName ?: current.fullName,
            phone = phone ?: current.phone,
            updatedAt = now,
            syncStatus = SyncStatus.PENDING_UPLOAD,
            lastSyncAttemptAt = now,
        )
        localDao.upsert(updated)

        val remote = runCatching {
            api.updateMe(ProfileUpdateDto(fullName = fullName, phone = phone))
        }.getOrNull()

        return if (remote != null) {
            cache(remote)
            localDao.getById(remote.id)
        } else {
            updated
        }
    }

    /** Wipes the cached profile. Call on sign-out or user-id mismatch. */
    suspend fun clearLocal() {
        Log.d(TAG, "clearLocal: wiping LocalProfile")
        localDao.clear()
    }

    private suspend fun cache(dto: ProfileDto) {
        val now = System.currentTimeMillis()
        localDao.upsert(
            LocalProfileEntity(
                id = dto.id,
                fullName = dto.fullName,
                phone = dto.phone,
                email = dto.email,
                createdAt = now,
                updatedAt = now,
                syncStatus = SyncStatus.SYNCED,
                lastSyncAttemptAt = now,
            )
        )
    }
}
