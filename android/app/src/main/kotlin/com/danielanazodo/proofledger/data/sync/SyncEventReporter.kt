package com.danielanazodo.proofledger.data.sync

import com.danielanazodo.proofledger.data.local.dao.LocalSyncEventDao
import com.danielanazodo.proofledger.data.local.entity.LocalSyncEventEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.SyncEventReportDto
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Flushes locally-logged sync attempts to the backend `sync_events` table.
 *
 * Reporting is **online-only and queued**: unreported rows stay queued until a
 * flush succeeds, which only happens with connectivity — exactly the condition
 * the most common reported failure ("no connectivity") describes. Ingest is
 * idempotent server-side (keyed by event id), so a partial flush that we fail
 * to record locally is safely deduped on the next attempt.
 */
@Singleton
class SyncEventReporter @Inject constructor(
    private val api: ApiService,
    private val dao: LocalSyncEventDao,
) {
    suspend fun flush() {
        while (true) {
            val batch = dao.unreported(BATCH)
            if (batch.isEmpty()) return

            var anyReported = false
            batch.groupBy { it.businessId }.forEach { (businessId, events) ->
                val sent = runCatching {
                    api.reportSyncEvents(businessId, events.map { it.toReportDto() })
                }.isSuccess
                if (sent) {
                    dao.markReported(events.map { it.id }, System.currentTimeMillis())
                    anyReported = true
                }
            }
            // Offline or every group failed — stop and retry on the next sync.
            if (!anyReported) return
            // Last page — nothing more to flush.
            if (batch.size < BATCH) return
        }
    }

    private fun LocalSyncEventEntity.toReportDto() = SyncEventReportDto(
        id = id,
        entityType = entityType,
        entityId = entityId,
        action = action,
        status = status,
        errorMessage = errorMessage,
        occurredAt = Instant.ofEpochMilli(createdAt).toString(),
    )

    private companion object {
        const val BATCH = 200
    }
}
