package com.danielanazodo.proofledger.data.sync

import com.danielanazodo.proofledger.data.local.dao.LocalSyncEventDao
import com.danielanazodo.proofledger.data.local.entity.LocalSyncEventEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncEventLogger @Inject constructor(
    private val dao: LocalSyncEventDao,
) {
    suspend fun log(
        businessId: String,
        entityType: String,
        entityId: String?,
        action: String,
        status: String,
        errorMessage: String? = null,
    ) {
        dao.insert(
            LocalSyncEventEntity(
                id = UUID.randomUUID().toString(),
                businessId = businessId,
                entityType = entityType,
                entityId = entityId,
                action = action,
                status = status,
                errorMessage = errorMessage?.take(500),
                createdAt = System.currentTimeMillis(),
            )
        )
    }
}
