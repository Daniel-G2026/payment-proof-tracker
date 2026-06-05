package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalSyncEventDao
import com.danielanazodo.proofledger.data.local.dao.LocalUnitDao
import com.danielanazodo.proofledger.data.local.entity.LocalSyncEventEntity
import com.danielanazodo.proofledger.data.local.entity.LocalUnitEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.UnitCreateDto
import com.danielanazodo.proofledger.data.remote.dto.UnitDto
import com.danielanazodo.proofledger.data.remote.dto.UnitUpdateDto
import com.danielanazodo.proofledger.data.sync.SyncEventLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class UnitRepositoryTest {
    private val businessId = "business-1"
    private val projectId = "project-1"

    @Test
    fun `createUnit syncs immediately, carries project and buyer`() = runBlocking {
        val dao = FakeUnitDao()
        val api = FakeApi()
        val events = FakeSyncEvents()
        val repo = UnitRepository(api.service, dao, SyncEventLogger(events))

        val created = repo.createUnit(
            businessId = businessId,
            projectId = projectId,
            label = "  Plot 12 ",
            buyerCounterpartyId = "buyer-1",
            totalPriceMinor = 200000000L,
        )

        assertEquals("Plot 12", created.label)
        assertEquals(projectId, created.projectId)
        assertEquals("buyer-1", created.buyerCounterpartyId)
        assertEquals(200000000L, created.totalPriceMinor)
        assertEquals(SyncStatus.SYNCED, dao.getByIdIncludingDeleted(created.id)!!.syncStatus)
        assertEquals(1, api.createCalls)
        assertEquals("unit", events.rows.single().entityType)
    }

    @Test
    fun `updateUnit assigns a buyer and syncs`() = runBlocking {
        val dao = FakeUnitDao()
        val api = FakeApi()
        dao.upsert(unit(syncStatus = SyncStatus.SYNCED, buyer = null))
        val repo = UnitRepository(api.service, dao, SyncEventLogger(FakeSyncEvents()))

        val updated = repo.updateUnit(unitId = "unit-1", buyerCounterpartyId = "buyer-9")

        assertEquals("buyer-9", updated!!.buyerCounterpartyId)
        assertEquals(SyncStatus.SYNCED, dao.getByIdIncludingDeleted("unit-1")!!.syncStatus)
        assertEquals(1, api.updateCalls)
    }

    @Test
    fun `createUnit offline keeps the row FAILED`() = runBlocking {
        val dao = FakeUnitDao()
        val api = FakeApi(createFailure = RuntimeException("offline"))
        val events = FakeSyncEvents()
        val repo = UnitRepository(api.service, dao, SyncEventLogger(events))

        val created = repo.createUnit(businessId, projectId, "Plot 1")

        assertEquals(SyncStatus.FAILED, dao.getByIdIncludingDeleted(created.id)!!.syncStatus)
        assertEquals("failure", events.rows.single().status)
    }

    @Test
    fun `syncPending drains a pending upload created offline`() = runBlocking {
        val dao = FakeUnitDao()
        val api = FakeApi()
        dao.upsert(unit(syncStatus = SyncStatus.PENDING_UPLOAD))
        val repo = UnitRepository(api.service, dao, SyncEventLogger(FakeSyncEvents()))

        repo.syncPending(businessId)

        assertEquals(SyncStatus.SYNCED, dao.getByIdIncludingDeleted("unit-1")!!.syncStatus)
        assertEquals(1, api.createCalls)
    }

    @Test
    fun `refreshFromRemote stores server units as SYNCED and observeByProject filters`() = runBlocking {
        val dao = FakeUnitDao()
        val api = FakeApi(
            remote = listOf(
                unitDto(id = "remote-1", label = "Plot A", project = projectId),
                unitDto(id = "remote-2", label = "Plot B", project = "other-project"),
            ),
        )
        val repo = UnitRepository(api.service, dao, SyncEventLogger(FakeSyncEvents()))

        repo.refreshFromRemote(businessId)

        assertTrue(repo.observeByBusiness(businessId).first().all { it.syncStatus == SyncStatus.SYNCED })
        val forProject = repo.observeByProject(projectId).first()
        assertEquals(listOf("Plot A"), forProject.map { it.label })
    }

    @Test
    fun `deleteUnit of a synced row soft-deletes via backend`() = runBlocking {
        val dao = FakeUnitDao()
        val api = FakeApi()
        dao.upsert(unit(syncStatus = SyncStatus.SYNCED))
        val repo = UnitRepository(api.service, dao, SyncEventLogger(FakeSyncEvents()))

        repo.deleteUnit("unit-1")

        assertEquals(SyncStatus.SYNCED, dao.getByIdIncludingDeleted("unit-1")!!.syncStatus)
        assertEquals(1, api.deleteCalls)
    }

    @Test
    fun `deleteUnit of a never-synced row removes it locally without a backend call`() = runBlocking {
        val dao = FakeUnitDao()
        val api = FakeApi()
        dao.upsert(unit(syncStatus = SyncStatus.PENDING_UPLOAD))
        val repo = UnitRepository(api.service, dao, SyncEventLogger(FakeSyncEvents()))

        repo.deleteUnit("unit-1")

        assertNull(dao.getByIdIncludingDeleted("unit-1"))
        assertEquals(0, api.deleteCalls)
    }

    private fun unit(
        id: String = "unit-1",
        syncStatus: SyncStatus,
        buyer: String? = "buyer-1",
    ): LocalUnitEntity = LocalUnitEntity(
        id = id,
        businessId = businessId,
        projectId = projectId,
        label = "Plot 12",
        buyerCounterpartyId = buyer,
        totalPriceMinor = null,
        currency = "NGN",
        status = "available",
        createdAt = 100L,
        updatedAt = 100L,
        deletedAt = null,
        syncStatus = syncStatus,
        lastSyncAttemptAt = null,
    )

    private fun unitDto(id: String, label: String, project: String): UnitDto = UnitDto(
        id = id,
        businessId = businessId,
        projectId = project,
        label = label,
        buyerCounterpartyId = null,
        totalPriceMinor = null,
        currency = "NGN",
        status = "available",
        createdAt = "2026-06-01T00:00:00Z",
        updatedAt = "2026-06-01T00:00:00Z",
        deletedAt = null,
    )

    private class FakeApi(
        private val remote: List<UnitDto> = emptyList(),
        private val createFailure: Throwable? = null,
    ) {
        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        private val base = Mockito.mock(ApiService::class.java)
        val service: ApiService = object : ApiService by base {
            override suspend fun listUnits(businessId: String, projectId: String?): List<UnitDto> = remote

            override suspend fun createUnit(businessId: String, body: UnitCreateDto): UnitDto {
                createCalls += 1
                createFailure?.let { throw it }
                return body.toDto(businessId)
            }

            override suspend fun updateUnit(
                businessId: String,
                unitId: String,
                body: UnitUpdateDto,
            ): UnitDto {
                updateCalls += 1
                return UnitDto(
                    id = unitId,
                    businessId = businessId,
                    projectId = "project-1",
                    label = body.label ?: "Plot 12",
                    buyerCounterpartyId = body.buyerCounterpartyId,
                    totalPriceMinor = body.totalPriceMinor,
                    currency = body.currency ?: "NGN",
                    status = body.status ?: "available",
                    createdAt = "2026-06-01T00:00:00Z",
                    updatedAt = "2026-06-02T00:00:00Z",
                    deletedAt = null,
                )
            }

            override suspend fun deleteUnit(businessId: String, unitId: String) {
                deleteCalls += 1
            }

            private fun UnitCreateDto.toDto(businessId: String): UnitDto = UnitDto(
                id = id,
                businessId = businessId,
                projectId = projectId,
                label = label,
                buyerCounterpartyId = buyerCounterpartyId,
                totalPriceMinor = totalPriceMinor,
                currency = currency,
                status = status ?: "available",
                createdAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z",
                deletedAt = null,
            )
        }
    }

    private class FakeSyncEvents : LocalSyncEventDao {
        val rows = mutableListOf<LocalSyncEventEntity>()
        override suspend fun insert(event: LocalSyncEventEntity) { rows += event }
        override fun observeRecent(businessId: String, limit: Int): Flow<List<LocalSyncEventEntity>> =
            flowOf(rows.filter { it.businessId == businessId }.take(limit))
        override suspend fun unreported(limit: Int): List<LocalSyncEventEntity> =
            rows.filter { it.reportedAt == null }.take(limit)
        override suspend fun markReported(ids: List<String>, now: Long) {
            rows.replaceAll { if (it.id in ids) it.copy(reportedAt = now) else it }
        }
        override fun observeFailures(): Flow<List<LocalSyncEventEntity>> =
            flowOf(rows.filter { it.status == "failure" })
        override suspend fun clear() { rows.clear() }
    }

    private class FakeUnitDao : LocalUnitDao {
        private val rows = linkedMapOf<String, LocalUnitEntity>()
        override suspend fun upsert(unit: LocalUnitEntity) { rows[unit.id] = unit }
        override suspend fun upsertAll(units: List<LocalUnitEntity>) = units.forEach { upsert(it) }
        override suspend fun getById(id: String): LocalUnitEntity? =
            rows[id]?.takeIf { it.deletedAt == null }
        override suspend fun getByIdIncludingDeleted(id: String): LocalUnitEntity? = rows[id]
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override suspend fun getRowsNeedingSync(businessId: String): List<LocalUnitEntity> =
            rows.values.filter {
                it.businessId == businessId &&
                    it.syncStatus in setOf(
                        SyncStatus.PENDING_UPLOAD,
                        SyncStatus.PENDING_UPDATE,
                        SyncStatus.PENDING_DELETE,
                        SyncStatus.FAILED,
                    )
            }.sortedBy { it.createdAt }
        override fun observeByBusiness(businessId: String): Flow<List<LocalUnitEntity>> =
            flowOf(rows.values.filter { it.businessId == businessId && it.deletedAt == null })
        override fun observeByProject(projectId: String): Flow<List<LocalUnitEntity>> =
            flowOf(rows.values.filter { it.projectId == projectId && it.deletedAt == null })
        override suspend fun clear() { rows.clear() }
    }
}
