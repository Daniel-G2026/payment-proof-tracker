package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalProfileDao
import com.danielanazodo.proofledger.data.local.entity.LocalProfileEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.ProfileDto
import com.danielanazodo.proofledger.data.remote.dto.ProfileUpdateDto
import com.danielanazodo.proofledger.util.DebugLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

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

    /** Live local mirror; emits again whenever the profile is saved. */
    fun observeProfile(): Flow<LocalProfileEntity?> = localDao.observeFirst()

    suspend fun refreshFromRemote(): LocalProfileEntity? {
        // A local edit that never reached the server must not be clobbered by
        // the stale remote copy — nothing else retries pending profile
        // uploads. Push it instead; on failure keep the local row as-is.
        val pending = localDao.getFirst()?.takeIf { it.syncStatus == SyncStatus.PENDING_UPLOAD }
        if (pending != null) {
            DebugLog.d(TAG) { "refreshFromRemote: pushing pending local profile edit" }
            val pushed = runCatching {
                api.updateMe(
                    ProfileUpdateDto(
                        fullName = pending.fullName,
                        // "" is the wire form of "clear" — a null field is
                        // omitted by Moshi and the backend would keep its value.
                        phone = pending.phone ?: "",
                    ),
                )
            }.getOrNull() ?: return pending
            cache(pushed)
            return localDao.getById(pushed.id)
        }

        DebugLog.d(TAG) { "refreshFromRemote: calling GET /me" }
        val result = runCatching { api.getMe() }
        val dto = result.getOrNull()
        if (dto == null) {
            DebugLog.e(
                TAG,
                { "refreshFromRemote: GET /me failed: ${result.exceptionOrNull()?.javaClass?.simpleName}" },
                result.exceptionOrNull(),
            )
            return null
        }
        DebugLog.d(TAG) { "refreshFromRemote: got profile id=${dto.id}" }
        cache(dto)
        return localDao.getById(dto.id)
    }

    /**
     * Save locally first, then attempt remote; a failed remote call leaves the
     * row PENDING_UPLOAD and [refreshFromRemote] pushes it on the next open.
     * Null means "leave unchanged"; a blank [phone] clears it.
     */
    suspend fun updateProfile(fullName: String?, phone: String?): LocalProfileEntity {
        val current = localDao.getFirst()
            ?: refreshFromRemote()
            ?: throw IllegalStateException("Could not save profile because no local or remote profile was found.")
        val now = System.currentTimeMillis()
        val updated = current.copy(
            fullName = fullName ?: current.fullName,
            phone = when {
                phone == null -> current.phone
                phone.isBlank() -> null
                else -> phone
            },
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
                ?: throw IllegalStateException("Could not load saved profile from local storage.")
        } else {
            updated
        }
    }

    /** Wipes the cached profile. Call on sign-out or user-id mismatch. */
    suspend fun clearLocal() {
        DebugLog.d(TAG) { "clearLocal: wiping LocalProfile" }
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
