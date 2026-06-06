package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalInstallmentDao
import com.danielanazodo.proofledger.data.local.dao.LocalScheduleVersionDao
import com.danielanazodo.proofledger.data.local.dao.LocalSyncEventDao
import com.danielanazodo.proofledger.data.local.entity.LocalInstallmentEntity
import com.danielanazodo.proofledger.data.local.entity.LocalScheduleVersionEntity
import com.danielanazodo.proofledger.data.local.entity.LocalSyncEventEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.InstallmentDto
import com.danielanazodo.proofledger.data.remote.dto.ScheduleVersionCreateDto
import com.danielanazodo.proofledger.data.remote.dto.ScheduleVersionDto
import com.danielanazodo.proofledger.data.sync.SyncEventLogger
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class ScheduleRepositoryTest {
    private val businessId = "business-1"
    private val unitId = "unit-1"

    private fun line(seq: Int, amount: Long, date: String) =
        ScheduleLineInput(seq, amount, LocalDate.parse(date))

    @Test
    fun `createScheduleVersion stores version and installments and syncs`() = runBlocking {
        val versionDao = FakeVersionDao()
        val installmentDao = FakeInstallmentDao()
        val api = FakeApi()
        val repo = ScheduleRepository(api.service, versionDao, installmentDao, SyncEventLogger(FakeEvents()))

        val created = repo.createScheduleVersion(
            businessId, unitId,
            listOf(line(1, 50000000, "2026-01-31"), line(2, 50000000, "2026-02-28")),
        )

        assertEquals(SyncStatus.SYNCED, versionDao.getByIdIncludingDeleted(created.id)!!.syncStatus)
        assertTrue(versionDao.getByIdIncludingDeleted(created.id)!!.isActive)
        assertEquals(2, installmentDao.getForVersion(created.id).size)
        assertEquals(1, api.createCalls)
    }

    @Test
    fun `renegotiation deactivates the prior active version`() = runBlocking {
        val versionDao = FakeVersionDao()
        val installmentDao = FakeInstallmentDao()
        val repo = ScheduleRepository(FakeApi().service, versionDao, installmentDao, SyncEventLogger(FakeEvents()))

        val v1 = repo.createScheduleVersion(businessId, unitId, listOf(line(1, 50000000, "2026-01-31")))
        val v2 = repo.createScheduleVersion(businessId, unitId, listOf(line(1, 40000000, "2026-02-28")))

        assertFalse(versionDao.getByIdIncludingDeleted(v1.id)!!.isActive)
        assertTrue(versionDao.getByIdIncludingDeleted(v2.id)!!.isActive)
        assertEquals(v2.id, versionDao.getActiveForUnit(unitId)!!.id)
        assertEquals(2, versionDao.getMaxVersionNo(unitId))
    }

    @Test
    fun `createScheduleVersion offline keeps version FAILED with installments intact`() = runBlocking {
        val versionDao = FakeVersionDao()
        val installmentDao = FakeInstallmentDao()
        val api = FakeApi(createFailure = RuntimeException("offline"))
        val repo = ScheduleRepository(api.service, versionDao, installmentDao, SyncEventLogger(FakeEvents()))

        val created = repo.createScheduleVersion(businessId, unitId, listOf(line(1, 50000000, "2026-01-31")))

        assertEquals(SyncStatus.FAILED, versionDao.getByIdIncludingDeleted(created.id)!!.syncStatus)
        assertEquals(1, installmentDao.getForVersion(created.id).size)
    }

    @Test
    fun `syncPending drains a pending version with its installments`() = runBlocking {
        val versionDao = FakeVersionDao()
        val installmentDao = FakeInstallmentDao()
        val api = FakeApi()
        versionDao.upsert(version(syncStatus = SyncStatus.PENDING_UPLOAD))
        installmentDao.upsert(installment("inst-1", "v1"))
        val repo = ScheduleRepository(api.service, versionDao, installmentDao, SyncEventLogger(FakeEvents()))

        repo.syncPending(businessId)

        assertEquals(SyncStatus.SYNCED, versionDao.getByIdIncludingDeleted("v1")!!.syncStatus)
        assertEquals(1, api.createCalls)
    }

    @Test
    fun `activeInstallments returns the active version's lines as engine domain`() = runBlocking {
        val versionDao = FakeVersionDao()
        val installmentDao = FakeInstallmentDao()
        versionDao.upsert(version(syncStatus = SyncStatus.SYNCED, isActive = true))
        installmentDao.upsert(installment("inst-1", "v1", sequence = 1, due = "2026-01-31"))
        installmentDao.upsert(installment("inst-2", "v1", sequence = 2, due = "2026-02-28"))
        val repo = ScheduleRepository(FakeApi().service, versionDao, installmentDao, SyncEventLogger(FakeEvents()))

        val domain = repo.activeInstallments(unitId)

        assertEquals(listOf(1, 2), domain.map { it.sequence })
        assertEquals(LocalDate.of(2026, 1, 31), domain.first().dueDate)
    }

    private fun version(
        id: String = "v1",
        syncStatus: SyncStatus,
        isActive: Boolean = true,
        versionNo: Int = 1,
    ) = LocalScheduleVersionEntity(
        id = id,
        businessId = businessId,
        unitId = unitId,
        versionNo = versionNo,
        source = "manual",
        isActive = isActive,
        offerLetterScreenshotId = null,
        createdAt = 100L,
        updatedAt = 100L,
        deletedAt = null,
        syncStatus = syncStatus,
        lastSyncAttemptAt = null,
    )

    private fun installment(
        id: String,
        versionId: String,
        sequence: Int = 1,
        amount: Long = 50000000,
        due: String = "2026-01-31",
    ) = LocalInstallmentEntity(
        id = id,
        businessId = businessId,
        scheduleVersionId = versionId,
        sequence = sequence,
        amountMinor = amount,
        dueDate = due,
        createdAt = 100L,
        updatedAt = 100L,
        deletedAt = null,
    )

    /** ApiService via delegation to a Mockito mock; only the schedule endpoints
     * are overridden. */
    private class FakeApi(private val createFailure: Throwable? = null) {
        var createCalls = 0
        private var versionCounter = 0
        private val base = Mockito.mock(ApiService::class.java)
        val service: ApiService = object : ApiService by base {
            override suspend fun listScheduleVersions(
                businessId: String,
                unitId: String,
            ): List<ScheduleVersionDto> = emptyList()

            override suspend fun createScheduleVersion(
                businessId: String,
                unitId: String,
                body: ScheduleVersionCreateDto,
            ): ScheduleVersionDto {
                createCalls += 1
                createFailure?.let { throw it }
                versionCounter += 1
                return ScheduleVersionDto(
                    id = body.id,
                    businessId = businessId,
                    unitId = unitId,
                    versionNo = versionCounter,
                    source = body.source,
                    isActive = true,
                    offerLetterScreenshotId = body.offerLetterScreenshotId,
                    createdAt = "2026-06-01T00:00:00Z",
                    updatedAt = "2026-06-01T00:00:00Z",
                    deletedAt = null,
                    installments = body.installments.map {
                        InstallmentDto(
                            id = it.id,
                            businessId = businessId,
                            scheduleVersionId = body.id,
                            sequence = it.sequence,
                            amountMinor = it.amountMinor,
                            dueDate = it.dueDate,
                            createdAt = "2026-06-01T00:00:00Z",
                            updatedAt = "2026-06-01T00:00:00Z",
                            deletedAt = null,
                        )
                    },
                )
            }
        }
    }

    private class FakeEvents : LocalSyncEventDao {
        val rows = mutableListOf<LocalSyncEventEntity>()
        override suspend fun insert(event: LocalSyncEventEntity) { rows += event }
        override fun observeRecent(businessId: String, limit: Int): Flow<List<LocalSyncEventEntity>> =
            flowOf(rows)
        override suspend fun unreported(limit: Int): List<LocalSyncEventEntity> = emptyList()
        override suspend fun markReported(ids: List<String>, now: Long) {}
        override fun observeFailures(): Flow<List<LocalSyncEventEntity>> = flowOf(emptyList())
        override suspend fun clear() { rows.clear() }
    }

    private class FakeVersionDao : LocalScheduleVersionDao {
        private val rows = linkedMapOf<String, LocalScheduleVersionEntity>()
        override suspend fun upsert(version: LocalScheduleVersionEntity) { rows[version.id] = version }
        override suspend fun upsertAll(versions: List<LocalScheduleVersionEntity>) = versions.forEach { upsert(it) }
        override suspend fun getById(id: String) = rows[id]?.takeIf { it.deletedAt == null }
        override suspend fun getByIdIncludingDeleted(id: String) = rows[id]
        override suspend fun getActiveForUnit(unitId: String) =
            rows.values.firstOrNull { it.unitId == unitId && it.isActive && it.deletedAt == null }
        override suspend fun getRowsNeedingSync(businessId: String) =
            rows.values.filter {
                it.businessId == businessId &&
                    it.syncStatus in setOf(
                        SyncStatus.PENDING_UPLOAD,
                        SyncStatus.PENDING_UPDATE,
                        SyncStatus.PENDING_DELETE,
                        SyncStatus.FAILED,
                    )
            }.sortedBy { it.versionNo }
        override fun observeByUnit(unitId: String): Flow<List<LocalScheduleVersionEntity>> =
            flowOf(rows.values.filter { it.unitId == unitId && it.deletedAt == null })
        override fun observeActiveForUnit(unitId: String): Flow<LocalScheduleVersionEntity?> =
            flowOf(rows.values.firstOrNull { it.unitId == unitId && it.isActive })
        override suspend fun getMaxVersionNo(unitId: String): Int =
            rows.values.filter { it.unitId == unitId }.maxOfOrNull { it.versionNo } ?: 0
        override suspend fun deactivateActiveForUnit(unitId: String) {
            rows.values.filter { it.unitId == unitId && it.isActive }.forEach {
                rows[it.id] = it.copy(isActive = false)
            }
        }
        override suspend fun clear() { rows.clear() }
    }

    private class FakeInstallmentDao : LocalInstallmentDao {
        private val rows = linkedMapOf<String, LocalInstallmentEntity>()
        override suspend fun upsert(installment: LocalInstallmentEntity) { rows[installment.id] = installment }
        override suspend fun upsertAll(installments: List<LocalInstallmentEntity>) = installments.forEach { upsert(it) }
        override suspend fun getForVersion(versionId: String) =
            rows.values.filter { it.scheduleVersionId == versionId && it.deletedAt == null }
                .sortedWith(compareBy({ it.dueDate }, { it.sequence }))
        override fun observeForVersion(versionId: String): Flow<List<LocalInstallmentEntity>> =
            flowOf(getForVersionSync(versionId))
        override suspend fun deleteForVersion(versionId: String) {
            rows.values.filter { it.scheduleVersionId == versionId }.forEach { rows.remove(it.id) }
        }
        override suspend fun clear() { rows.clear() }
        private fun getForVersionSync(versionId: String) =
            rows.values.filter { it.scheduleVersionId == versionId && it.deletedAt == null }
    }
}
