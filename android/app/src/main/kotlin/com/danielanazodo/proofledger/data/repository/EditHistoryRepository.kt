package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.ui.history.EditHistoryEntry
import com.danielanazodo.proofledger.ui.history.EditHistoryMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches an entity's edit history from the backend audit log. This is a
 * read-only, online-only secondary view — there's no local cache because the
 * diffs only exist server-side. Callers handle the offline/failure case via
 * the returned [Result].
 */
@Singleton
class EditHistoryRepository @Inject constructor(
    private val api: ApiService,
) {
    suspend fun load(
        businessId: String,
        entityType: String,
        entityId: String,
    ): Result<List<EditHistoryEntry>> = runCatching {
        EditHistoryMapper.toEntries(api.listAuditEvents(businessId, entityType, entityId))
    }
}
