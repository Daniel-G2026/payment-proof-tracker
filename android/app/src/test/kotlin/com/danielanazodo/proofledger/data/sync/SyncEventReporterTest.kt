package com.danielanazodo.proofledger.data.sync

import com.danielanazodo.proofledger.data.local.dao.LocalSyncEventDao
import com.danielanazodo.proofledger.data.local.entity.LocalSyncEventEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.SyncEventIngestResultDto
import com.danielanazodo.proofledger.data.remote.dto.SyncEventReportDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class SyncEventReporterTest {

    private class FakeSyncEventDao : LocalSyncEventDao {
        val rows = mutableListOf<LocalSyncEventEntity>()
        override suspend fun insert(event: LocalSyncEventEntity) {
            rows.removeAll { it.id == event.id }
            rows += event
        }
        override fun observeRecent(businessId: String, limit: Int):
            Flow<List<LocalSyncEventEntity>> = emptyFlow()
        override fun observeFailures(): Flow<List<LocalSyncEventEntity>> = emptyFlow()
        override suspend fun unreported(limit: Int): List<LocalSyncEventEntity> =
            rows.filter { it.reportedAt == null }.sortedBy { it.createdAt }.take(limit)
        override suspend fun markReported(ids: List<String>, now: Long) {
            rows.replaceAll { if (it.id in ids) it.copy(reportedAt = now) else it }
        }
        override suspend fun clear() = rows.clear()
    }

    /** ApiService via interface delegation to a Mockito mock, overriding only
     * reportSyncEvents — avoids hand-stubbing all 33 methods. */
    private fun api(
        record: MutableList<Pair<String, List<SyncEventReportDto>>>,
        fail: Boolean = false,
    ): ApiService {
        val base = Mockito.mock(ApiService::class.java)
        return object : ApiService by base {
            override suspend fun reportSyncEvents(
                businessId: String,
                events: List<SyncEventReportDto>,
            ): SyncEventIngestResultDto {
                if (fail) throw RuntimeException("offline")
                record += businessId to events
                return SyncEventIngestResultDto(events.size, 0)
            }
        }
    }

    private fun event(id: String, businessId: String, reported: Boolean = false) =
        LocalSyncEventEntity(
            id = id,
            businessId = businessId,
            entityType = "transaction",
            entityId = "t-$id",
            action = "create",
            status = "failure",
            errorMessage = "No connectivity",
            createdAt = id.removePrefix("e").toLong(),
            reportedAt = if (reported) 1L else null,
        )

    @Test
    fun `flush posts unreported events and marks them reported`() = runBlocking {
        val dao = FakeSyncEventDao()
        dao.rows += listOf(event("e1", "b1"), event("e2", "b1"))
        val posted = mutableListOf<Pair<String, List<SyncEventReportDto>>>()

        SyncEventReporter(api(posted), dao).flush()

        assertEquals(1, posted.size)
        assertEquals("b1", posted.single().first)
        assertEquals(2, posted.single().second.size)
        assertTrue(dao.rows.all { it.reportedAt != null })
    }

    @Test
    fun `flush groups events by business`() = runBlocking {
        val dao = FakeSyncEventDao()
        dao.rows += listOf(event("e1", "b1"), event("e2", "b2"))
        val posted = mutableListOf<Pair<String, List<SyncEventReportDto>>>()

        SyncEventReporter(api(posted), dao).flush()

        assertEquals(setOf("b1", "b2"), posted.map { it.first }.toSet())
        assertTrue(dao.rows.all { it.reportedAt != null })
    }

    @Test
    fun `flush leaves events unreported when offline`() = runBlocking {
        val dao = FakeSyncEventDao()
        dao.rows += listOf(event("e1", "b1"))
        val posted = mutableListOf<Pair<String, List<SyncEventReportDto>>>()

        SyncEventReporter(api(posted, fail = true), dao).flush()

        assertTrue(posted.isEmpty())
        assertNull(dao.rows.single().reportedAt)
    }

    @Test
    fun `flush ignores already-reported events`() = runBlocking {
        val dao = FakeSyncEventDao()
        dao.rows += listOf(event("e1", "b1", reported = true))
        val posted = mutableListOf<Pair<String, List<SyncEventReportDto>>>()

        SyncEventReporter(api(posted), dao).flush()

        assertTrue(posted.isEmpty())
    }
}
