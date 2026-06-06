package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalCategoryDao
import com.danielanazodo.proofledger.data.local.dao.LocalSyncEventDao
import com.danielanazodo.proofledger.data.local.entity.LocalCategoryEntity
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
import com.danielanazodo.proofledger.data.remote.dto.TransactionDraftConfirmDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionDraftDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionUpdateDto
import com.danielanazodo.proofledger.data.sync.SyncEventLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class CategoryRepositoryTest {
    private val businessId = "business-1"

    @Test
    fun `createCategory syncs immediately and marks the row SYNCED`() = runBlocking {
        val dao = FakeCategoryDao()
        val api = FakeApiService()
        val events = FakeSyncEventDao()
        val repository = CategoryRepository(api, dao, SyncEventLogger(events))

        val created = repository.createCategory(businessId, "  Sand  Delivery ")

        assertEquals("Sand  Delivery", created.name)
        assertEquals("sand delivery", created.normalizedName)
        assertEquals(SyncStatus.SYNCED, dao.getByIdIncludingDeleted(created.id)!!.syncStatus)
        assertEquals(1, api.createCategoryCalls)
        val event = events.rows.single()
        assertEquals("category", event.entityType)
        assertEquals("create", event.action)
        assertEquals("success", event.status)
    }

    @Test
    fun `createCategory offline keeps the row PENDING_UPLOAD and logs failure`() = runBlocking {
        val dao = FakeCategoryDao()
        val api = FakeApiService(createCategoryFailure = RuntimeException("offline"))
        val events = FakeSyncEventDao()
        val repository = CategoryRepository(api, dao, SyncEventLogger(events))

        val created = repository.createCategory(businessId, "Sand")

        val stored = dao.getByIdIncludingDeleted(created.id)!!
        assertEquals(SyncStatus.FAILED, stored.syncStatus)
        assertEquals("failure", events.rows.single().status)
    }

    @Test
    fun `syncPending drains a pending upload created offline`() = runBlocking {
        val dao = FakeCategoryDao()
        val api = FakeApiService()
        val events = FakeSyncEventDao()
        dao.upsert(category(syncStatus = SyncStatus.PENDING_UPLOAD))
        val repository = CategoryRepository(api, dao, SyncEventLogger(events))

        repository.syncPending(businessId)

        assertEquals(SyncStatus.SYNCED, dao.getByIdIncludingDeleted("category-1")!!.syncStatus)
        assertEquals(1, api.createCategoryCalls)
    }

    @Test
    fun `refreshFromRemote stores server categories as SYNCED`() = runBlocking {
        val dao = FakeCategoryDao()
        val api = FakeApiService(
            remoteCategories = listOf(
                categoryDto(id = "remote-1", name = "Cement"),
                categoryDto(id = "remote-2", name = "Labour"),
            ),
        )
        val repository = CategoryRepository(api, dao, SyncEventLogger(FakeSyncEventDao()))

        repository.refreshFromRemote(businessId)

        val rows = repository.observeByBusiness(businessId).first()
        assertEquals(setOf("Cement", "Labour"), rows.map { it.name }.toSet())
        assertTrue(rows.all { it.syncStatus == SyncStatus.SYNCED })
    }

    @Test
    fun `stale pending update becomes conflict instead of retry failure`() = runBlocking {
        val dao = FakeCategoryDao()
        val api = FakeApiService(updateCategoryFailure = http409())
        val events = FakeSyncEventDao()
        dao.upsert(category(syncStatus = SyncStatus.PENDING_UPDATE))
        val repository = CategoryRepository(api, dao, SyncEventLogger(events))

        repository.syncPending(businessId)

        val updated = dao.getByIdIncludingDeleted("category-1")!!
        assertEquals(SyncStatus.CONFLICT, updated.syncStatus)
        assertEquals("failure", events.rows.single().status)
    }

    @Test
    fun `pending delete treats backend 404 as terminal success`() = runBlocking {
        val dao = FakeCategoryDao()
        val api = FakeApiService(deleteCategory404 = true)
        val events = FakeSyncEventDao()
        dao.upsert(
            category(
                syncStatus = SyncStatus.PENDING_DELETE,
                deletedAt = 123L,
                lastSyncError = "Previous failure",
            )
        )
        val repository = CategoryRepository(api, dao, SyncEventLogger(events))

        repository.syncPending(businessId)

        val updated = dao.getByIdIncludingDeleted("category-1")!!
        assertEquals(SyncStatus.SYNCED, updated.syncStatus)
        assertNull(updated.lastSyncError)
        assertEquals(1, api.deleteCategoryCalls)
        assertEquals("delete", events.rows.single().action)
    }

    @Test
    fun `deleteCategory of a never-synced row removes it locally without a backend call`() = runBlocking {
        val dao = FakeCategoryDao()
        val api = FakeApiService()
        val events = FakeSyncEventDao()
        dao.upsert(category(syncStatus = SyncStatus.PENDING_UPLOAD))
        val repository = CategoryRepository(api, dao, SyncEventLogger(events))

        repository.deleteCategory("category-1")

        assertNull(dao.getByIdIncludingDeleted("category-1"))
        assertEquals(0, api.deleteCategoryCalls)
        assertTrue(events.rows.isEmpty())
    }

    @Test
    fun `deleteCategory of a synced row (e g a default) soft-deletes via backend`() = runBlocking {
        val dao = FakeCategoryDao()
        val api = FakeApiService()
        val events = FakeSyncEventDao()
        dao.upsert(category(syncStatus = SyncStatus.SYNCED))
        val repository = CategoryRepository(api, dao, SyncEventLogger(events))

        repository.deleteCategory("category-1")

        val updated = dao.getByIdIncludingDeleted("category-1")!!
        assertEquals(SyncStatus.SYNCED, updated.syncStatus)
        assertEquals(1, api.deleteCategoryCalls)
        assertEquals("delete", events.rows.single().action)
        assertEquals("success", events.rows.single().status)
    }

    private fun http409(): HttpException =
        HttpException(Response.error<Any>(409, "".toResponseBody(null)))

    private fun category(
        id: String = "category-1",
        syncStatus: SyncStatus,
        deletedAt: Long? = null,
        lastSyncError: String? = null,
    ): LocalCategoryEntity = LocalCategoryEntity(
        id = id,
        businessId = businessId,
        name = "Cement",
        normalizedName = "cement",
        isArchived = false,
        createdAt = 100L,
        updatedAt = 100L,
        deletedAt = deletedAt,
        syncStatus = syncStatus,
        lastSyncAttemptAt = null,
        lastSyncError = lastSyncError,
    )

    private fun categoryDto(id: String, name: String): CategoryDto = CategoryDto(
        id = id,
        businessId = businessId,
        name = name,
        normalizedName = name.trim().lowercase(),
        isArchived = false,
        createdAt = "2026-06-01T00:00:00Z",
        updatedAt = "2026-06-01T00:00:00Z",
        deletedAt = null,
    )

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

    private class FakeCategoryDao : LocalCategoryDao {
        private val rows = linkedMapOf<String, LocalCategoryEntity>()

        override suspend fun upsert(category: LocalCategoryEntity) {
            rows[category.id] = category
        }

        override suspend fun upsertAll(categories: List<LocalCategoryEntity>) {
            categories.forEach { upsert(it) }
        }

        override suspend fun getById(id: String): LocalCategoryEntity? =
            rows[id]?.takeIf { it.deletedAt == null }

        override suspend fun getByIdIncludingDeleted(id: String): LocalCategoryEntity? =
            rows[id]

        override suspend fun deleteById(id: String) {
            rows.remove(id)
        }

        override suspend fun getRowsNeedingSync(businessId: String): List<LocalCategoryEntity> =
            rows.values
                .filter {
                    it.businessId == businessId &&
                        it.syncStatus in setOf(
                            SyncStatus.PENDING_UPLOAD,
                            SyncStatus.PENDING_UPDATE,
                            SyncStatus.PENDING_DELETE,
                            SyncStatus.FAILED,
                        )
                }
                .sortedBy { it.createdAt }

        override fun observeByBusiness(businessId: String): Flow<List<LocalCategoryEntity>> =
            flowOf(
                rows.values.filter {
                    it.businessId == businessId && it.deletedAt == null && !it.isArchived
                }
            )

        override suspend fun clear() {
            rows.clear()
        }
    }

    private class FakeApiService(
        private val remoteCategories: List<CategoryDto> = emptyList(),
        private val createCategoryFailure: Throwable? = null,
        private val updateCategoryFailure: Throwable? = null,
        private val deleteCategory404: Boolean = false,
    ) : ApiService {
        override suspend fun listScheduleVersions(
            businessId: String,
            unitId: String,
        ): List<com.danielanazodo.proofledger.data.remote.dto.ScheduleVersionDto> = emptyList()
        override suspend fun createScheduleVersion(
            businessId: String,
            unitId: String,
            body: com.danielanazodo.proofledger.data.remote.dto.ScheduleVersionCreateDto,
        ): com.danielanazodo.proofledger.data.remote.dto.ScheduleVersionDto = error("not used")
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
        var createCategoryCalls = 0
        var deleteCategoryCalls = 0

        override suspend fun listCategories(
            businessId: String,
            search: String?,
        ): List<CategoryDto> = remoteCategories

        override suspend fun createCategory(
            businessId: String,
            body: CategoryCreateDto,
        ): CategoryDto {
            createCategoryCalls += 1
            createCategoryFailure?.let { throw it }
            return CategoryDto(
                id = body.id,
                businessId = businessId,
                name = body.name,
                // Mirror the backend's _normalize_name (collapses internal whitespace).
                normalizedName = body.name.trim().lowercase().replace(Regex("\\s+"), " "),
                isArchived = false,
                createdAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z",
                deletedAt = null,
            )
        }

        override suspend fun updateCategory(
            businessId: String,
            categoryId: String,
            body: CategoryUpdateDto,
        ): CategoryDto {
            updateCategoryFailure?.let { throw it }
            return CategoryDto(
                id = categoryId,
                businessId = businessId,
                name = body.name ?: "Cement",
                normalizedName = (body.name ?: "Cement").trim().lowercase(),
                isArchived = body.isArchived ?: false,
                createdAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z",
                deletedAt = null,
            )
        }

        override suspend fun deleteCategory(businessId: String, categoryId: String) {
            deleteCategoryCalls += 1
            if (deleteCategory404) {
                throw HttpException(Response.error<Any>(404, "".toResponseBody(null)))
            }
        }

        override suspend fun listCounterpartyTypes(
            businessId: String,
            search: String?,
        ): List<com.danielanazodo.proofledger.data.remote.dto.CounterpartyTypeDto> = emptyList()
        override suspend fun createCounterpartyType(
            businessId: String,
            body: com.danielanazodo.proofledger.data.remote.dto.CounterpartyTypeCreateDto,
        ): com.danielanazodo.proofledger.data.remote.dto.CounterpartyTypeDto = error("not used")
        override suspend fun updateCounterpartyType(
            businessId: String,
            typeId: String,
            body: com.danielanazodo.proofledger.data.remote.dto.CounterpartyTypeUpdateDto,
        ): com.danielanazodo.proofledger.data.remote.dto.CounterpartyTypeDto = error("not used")
        override suspend fun deleteCounterpartyType(businessId: String, typeId: String) {}

        override suspend fun health() = HealthResponse("ok")
        override suspend fun getMe(): ProfileDto = error("not used")
        override suspend fun updateMe(body: ProfileUpdateDto): ProfileDto = error("not used")
        override suspend fun listBusinesses(): List<BusinessDto> = emptyList()
        override suspend fun createBusiness(body: BusinessCreateDto): BusinessDto = error("not used")
        override suspend fun getBusiness(id: String): BusinessDto = error("not used")
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
        ): ResponseBody = error("not used")
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
