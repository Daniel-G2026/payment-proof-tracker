package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.analytics.NoOpAnalytics
import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.dao.LocalBusinessDao
import com.danielanazodo.proofledger.data.local.dao.LocalSyncEventDao
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity
import com.danielanazodo.proofledger.data.local.entity.LocalBusinessEntity
import com.danielanazodo.proofledger.data.local.entity.LocalSyncEventEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.HealthResponse
import com.danielanazodo.proofledger.data.remote.dto.BusinessCreateDto
import com.danielanazodo.proofledger.data.remote.dto.BusinessDto
import com.danielanazodo.proofledger.data.remote.dto.CategoryCreateDto
import com.danielanazodo.proofledger.data.remote.dto.CategoryDto
import com.danielanazodo.proofledger.data.remote.dto.CategoryUpdateDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyCreateDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartySummaryDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyUpdateDto
import com.danielanazodo.proofledger.data.remote.dto.DuplicateCandidateDto
import com.danielanazodo.proofledger.data.remote.dto.DuplicateCandidateRequestDto
import com.danielanazodo.proofledger.data.remote.dto.ProfileDto
import com.danielanazodo.proofledger.data.remote.dto.ProfileUpdateDto
import com.danielanazodo.proofledger.data.remote.dto.ScreenshotDto
import com.danielanazodo.proofledger.data.remote.dto.SignedScreenshotUrlDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionCreateDto
import okhttp3.ResponseBody
import com.danielanazodo.proofledger.data.remote.dto.TransactionDraftConfirmDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionDraftDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionUpdateDto
import com.danielanazodo.proofledger.data.sync.SyncEventLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.Assert.assertEquals
import org.junit.Test

class BusinessRepositoryTest {
    @Test
    fun `failed pending business sync writes local sync event`() = runBlocking {
        val businessDao = FakeBusinessDao()
        val events = FakeSyncEventDao()
        val row = business(syncStatus = SyncStatus.PENDING_UPLOAD)
        businessDao.upsert(row)
        val repository = BusinessRepository(
            api = FakeApiService(createBusinessFailure = RuntimeException("offline")),
            businessDao = businessDao,
            metadataDao = FakeMetadataDao(),
            syncEventLogger = SyncEventLogger(events),
            analytics = NoOpAnalytics(),
        )

        repository.syncPending()

        val updated = businessDao.getById(row.id)!!
        assertEquals(SyncStatus.FAILED, updated.syncStatus)
        val event = events.rows.single()
        assertEquals(row.id, event.businessId)
        assertEquals("business", event.entityType)
        assertEquals(row.id, event.entityId)
        assertEquals("create", event.action)
        assertEquals("failure", event.status)
    }

    @Test
    fun `successful pending business sync writes local sync event`() = runBlocking {
        val businessDao = FakeBusinessDao()
        val events = FakeSyncEventDao()
        val row = business(syncStatus = SyncStatus.FAILED)
        businessDao.upsert(row)
        val repository = BusinessRepository(
            api = FakeApiService(),
            businessDao = businessDao,
            metadataDao = FakeMetadataDao(),
            syncEventLogger = SyncEventLogger(events),
            analytics = NoOpAnalytics(),
        )

        repository.syncPending()

        assertEquals(SyncStatus.SYNCED, businessDao.getById(row.id)!!.syncStatus)
        assertEquals("success", events.rows.single().status)
    }

    private fun business(
        id: String = "business-1",
        syncStatus: SyncStatus,
    ): LocalBusinessEntity = LocalBusinessEntity(
        id = id,
        ownerId = "owner-1",
        name = "Ledger Co",
        country = "Nigeria",
        currency = "NGN",
        businessType = null,
        plan = "free",
        createdAt = 100L,
        updatedAt = 100L,
        syncStatus = syncStatus,
        lastSyncAttemptAt = null,
        lastSyncError = null,
    )

    private class FakeBusinessDao : LocalBusinessDao {
        private val rows = linkedMapOf<String, LocalBusinessEntity>()

        override suspend fun upsert(business: LocalBusinessEntity) {
            rows[business.id] = business
        }

        override suspend fun upsertAll(businesses: List<LocalBusinessEntity>) {
            businesses.forEach { upsert(it) }
        }

        override suspend fun getById(id: String): LocalBusinessEntity? = rows[id]

        override suspend fun getAll(): List<LocalBusinessEntity> = rows.values.toList()

        override suspend fun count(): Int = rows.size

        override suspend fun getRowsNeedingSync(): List<LocalBusinessEntity> =
            rows.values
                .filter {
                    it.syncStatus in setOf(
                        SyncStatus.PENDING_UPLOAD,
                        SyncStatus.PENDING_UPDATE,
                        SyncStatus.PENDING_DELETE,
                        SyncStatus.FAILED,
                    )
                }
                .sortedBy { it.createdAt }

        override suspend fun clear() {
            rows.clear()
        }
    }

    private class FakeMetadataDao : LocalAppMetadataDao {
        private val rows = mutableMapOf<String, LocalAppMetadataEntity>()

        override suspend fun upsert(entry: LocalAppMetadataEntity) {
            rows[entry.key] = entry
        }

        override suspend fun get(key: String): LocalAppMetadataEntity? = rows[key]
    }

    private class FakeSyncEventDao : LocalSyncEventDao {
        val rows = mutableListOf<LocalSyncEventEntity>()

        override suspend fun insert(event: LocalSyncEventEntity) {
            rows += event
        }

        override fun observeRecent(
            businessId: String,
            limit: Int,
        ): Flow<List<LocalSyncEventEntity>> =
            flowOf(rows.filter { it.businessId == businessId }.take(limit))

        override suspend fun unreported(limit: Int): List<LocalSyncEventEntity> =
            rows.filter { it.reportedAt == null }.take(limit)

        override suspend fun markReported(ids: List<String>, now: Long) {
            rows.replaceAll { if (it.id in ids) it.copy(reportedAt = now) else it }
        }

        override fun observeFailures(): Flow<List<LocalSyncEventEntity>> =
            flowOf(rows.filter { it.status == "failure" })

        override suspend fun clear() {
            rows.clear()
        }
    }

    private class FakeApiService(
        private val createBusinessFailure: Throwable? = null,
    ) : ApiService {
        override suspend fun listProjects(
            businessId: String,
        ): List<com.danielanazodo.proofledger.data.remote.dto.ProjectDto> = emptyList()
        override suspend fun createProject(
            businessId: String,
            body: com.danielanazodo.proofledger.data.remote.dto.ProjectCreateDto,
        ): com.danielanazodo.proofledger.data.remote.dto.ProjectDto = error("not used")
        override suspend fun updateProject(
            businessId: String,
            projectId: String,
            body: com.danielanazodo.proofledger.data.remote.dto.ProjectUpdateDto,
        ): com.danielanazodo.proofledger.data.remote.dto.ProjectDto = error("not used")
        override suspend fun deleteProject(businessId: String, projectId: String) {}
        override suspend fun listUnits(
            businessId: String,
            projectId: String?,
        ): List<com.danielanazodo.proofledger.data.remote.dto.UnitDto> = emptyList()
        override suspend fun createUnit(
            businessId: String,
            body: com.danielanazodo.proofledger.data.remote.dto.UnitCreateDto,
        ): com.danielanazodo.proofledger.data.remote.dto.UnitDto = error("not used")
        override suspend fun updateUnit(
            businessId: String,
            unitId: String,
            body: com.danielanazodo.proofledger.data.remote.dto.UnitUpdateDto,
        ): com.danielanazodo.proofledger.data.remote.dto.UnitDto = error("not used")
        override suspend fun deleteUnit(businessId: String, unitId: String) {}
        override suspend fun listAuditEvents(
            businessId: String,
            entityType: String,
            entityId: String,
        ): List<com.danielanazodo.proofledger.data.remote.dto.AuditEventDto> = emptyList()
        override suspend fun reportSyncEvents(
            businessId: String,
            events: List<com.danielanazodo.proofledger.data.remote.dto.SyncEventReportDto>,
        ): com.danielanazodo.proofledger.data.remote.dto.SyncEventIngestResultDto =
            com.danielanazodo.proofledger.data.remote.dto.SyncEventIngestResultDto(events.size, 0)
        override suspend fun createBusiness(body: BusinessCreateDto): BusinessDto {
            createBusinessFailure?.let { throw it }
            return BusinessDto(
                id = body.id,
                ownerId = "owner-1",
                name = body.name,
                country = body.country,
                currency = body.currency,
                businessType = body.businessType,
                plan = "free",
                createdAt = "2026-05-29T00:00:00Z",
                updatedAt = "2026-05-29T00:00:00Z",
            )
        }

        override suspend fun health() = HealthResponse("ok")
        override suspend fun getMe(): ProfileDto = error("not used")
        override suspend fun updateMe(body: ProfileUpdateDto): ProfileDto = error("not used")
        override suspend fun listBusinesses(): List<BusinessDto> = emptyList()
        override suspend fun getBusiness(id: String): BusinessDto = error("not used")
        override suspend fun listCategories(
            businessId: String,
            search: String?,
        ): List<CategoryDto> = emptyList()
        override suspend fun createCategory(
            businessId: String,
            body: CategoryCreateDto,
        ): CategoryDto = error("not used")
        override suspend fun updateCategory(
            businessId: String,
            categoryId: String,
            body: CategoryUpdateDto,
        ): CategoryDto = error("not used")
        override suspend fun deleteCategory(businessId: String, categoryId: String) {}
        override suspend fun listCounterparties(
            businessId: String,
            search: String?,
            type: String?,
        ): List<CounterpartyDto> = emptyList()
        override suspend fun createCounterparty(
            businessId: String,
            body: CounterpartyCreateDto,
        ): CounterpartyDto = error("not used")
        override suspend fun getCounterparty(
            businessId: String,
            counterpartyId: String,
        ): CounterpartyDto = error("not used")
        override suspend fun updateCounterparty(
            businessId: String,
            counterpartyId: String,
            body: CounterpartyUpdateDto,
        ): CounterpartyDto = error("not used")
        override suspend fun deleteCounterparty(businessId: String, counterpartyId: String) {}
        override suspend fun getCounterpartySummary(
            businessId: String,
            counterpartyId: String,
        ): CounterpartySummaryDto = error("not used")
        override suspend fun listTransactions(
            businessId: String,
            direction: String?,
            search: String?,
            startDate: String?,
            endDate: String?,
        ): List<TransactionDto> = emptyList()
        override suspend fun createTransaction(
            businessId: String,
            body: TransactionCreateDto,
        ): TransactionDto = error("not used")
        override suspend fun duplicateCandidates(
            businessId: String,
            body: DuplicateCandidateRequestDto,
        ): List<DuplicateCandidateDto> = emptyList()
        override suspend fun getTransaction(
            businessId: String,
            transactionId: String,
        ): TransactionDto = error("not used")
        override suspend fun updateTransaction(
            businessId: String,
            transactionId: String,
            body: TransactionUpdateDto,
        ): TransactionDto = error("not used")
        override suspend fun deleteTransaction(businessId: String, transactionId: String) {}
        override suspend fun uploadScreenshot(
            businessId: String,
            id: RequestBody,
            transactionId: RequestBody,
            originalFileSize: RequestBody,
            width: RequestBody,
            height: RequestBody,
            file: MultipartBody.Part,
        ): ScreenshotDto = error("not used")
        override suspend fun uploadOrphanScreenshot(
            businessId: String,
            id: RequestBody,
            originalFileSize: RequestBody,
            width: RequestBody,
            height: RequestBody,
            file: MultipartBody.Part,
        ): ScreenshotDto = error("not used")
        override suspend fun deleteScreenshot(screenshotId: String) {}
        override suspend fun exportPdf(
            businessId: String,
            startDate: String,
            endDate: String,
        ): okhttp3.ResponseBody = error("not used")
        override suspend fun listScreenshots(
            businessId: String,
            limit: Int,
            offset: Int,
        ): List<ScreenshotDto> = emptyList()
        override suspend fun downloadProofBytes(url: String): ResponseBody = error("not used")
        override suspend fun getScreenshotSignedUrl(screenshotId: String): SignedScreenshotUrlDto =
            error("not used")
        override suspend fun getScreenshotDraft(screenshotId: String): TransactionDraftDto =
            error("not used")
        override suspend fun confirmDraft(
            draftId: String,
            body: TransactionDraftConfirmDto,
        ): TransactionDraftDto = error("not used")
    }
}
