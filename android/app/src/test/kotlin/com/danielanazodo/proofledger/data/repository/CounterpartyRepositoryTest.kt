package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalCounterpartyDao
import com.danielanazodo.proofledger.data.local.dao.LocalSyncEventDao
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class CounterpartyRepositoryTest {
    private val businessId = "business-1"

    @Test
    fun `delete of pending upload counterparty coalesces locally without backend call`() = runBlocking {
        val dao = FakeCounterpartyDao()
        val api = FakeApiService()
        val row = counterparty(syncStatus = SyncStatus.PENDING_UPLOAD)
        dao.upsert(row)
        val events = FakeSyncEventDao()
        val repository = CounterpartyRepository(api, dao, SyncEventLogger(events))

        repository.deleteCounterparty(row.id)

        assertNull(dao.getByIdIncludingDeleted(row.id))
        assertEquals(0, api.deleteCounterpartyCalls)
        assertEquals(emptyList<LocalSyncEventEntity>(), events.rows)
    }

    @Test
    fun `pending delete treats backend 404 as terminal success`() = runBlocking {
        val dao = FakeCounterpartyDao()
        val api = FakeApiService(deleteCounterparty404 = true)
        val row = counterparty(
            syncStatus = SyncStatus.PENDING_DELETE,
            deletedAt = 123L,
            lastSyncError = "Previous failure",
        )
        dao.upsert(row)
        val events = FakeSyncEventDao()
        val repository = CounterpartyRepository(api, dao, SyncEventLogger(events))

        repository.syncPending(businessId)

        val updated = dao.getByIdIncludingDeleted(row.id)!!
        assertEquals(SyncStatus.SYNCED, updated.syncStatus)
        assertNull(updated.lastSyncError)
        assertEquals(1, api.deleteCounterpartyCalls)
        assertEquals("delete", events.rows.single().action)
        assertEquals("success", events.rows.single().status)
    }

    @Test
    fun `failed pending upload writes local sync event`() = runBlocking {
        val dao = FakeCounterpartyDao()
        val api = FakeApiService(createCounterpartyFailure = RuntimeException("offline"))
        val events = FakeSyncEventDao()
        val row = counterparty(syncStatus = SyncStatus.PENDING_UPLOAD)
        dao.upsert(row)
        val repository = CounterpartyRepository(api, dao, SyncEventLogger(events))

        repository.syncPending(businessId)

        val updated = dao.getByIdIncludingDeleted(row.id)!!
        assertEquals(SyncStatus.FAILED, updated.syncStatus)
        val event = events.rows.single()
        assertEquals("person", event.entityType)
        assertEquals("create", event.action)
        assertEquals("failure", event.status)
    }

    @Test
    fun `stale pending update becomes conflict instead of retry failure`() = runBlocking {
        val dao = FakeCounterpartyDao()
        val api = FakeApiService(updateCounterpartyFailure = http409())
        val events = FakeSyncEventDao()
        val row = counterparty(syncStatus = SyncStatus.PENDING_UPDATE)
        dao.upsert(row)
        val repository = CounterpartyRepository(api, dao, SyncEventLogger(events))

        repository.syncPending(businessId)

        val updated = dao.getByIdIncludingDeleted(row.id)!!
        assertEquals(SyncStatus.CONFLICT, updated.syncStatus)
        assertEquals("Server has a newer version of this item.", updated.lastSyncError)
        assertEquals("failure", events.rows.single().status)
    }

    private fun http409(): HttpException =
        HttpException(Response.error<Any>(409, "".toResponseBody(null)))

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

        override fun observeFailures(): Flow<List<LocalSyncEventEntity>> =
            flowOf(rows.filter { it.status == "failure" })

        override suspend fun clear() {
            rows.clear()
        }
    }

    private fun counterparty(
        id: String = "person-1",
        syncStatus: SyncStatus,
        deletedAt: Long? = null,
        lastSyncError: String? = null,
    ): LocalCounterpartyEntity = LocalCounterpartyEntity(
        id = id,
        businessId = businessId,
        name = "Adewale Supplies",
        normalizedName = "adewale supplies",
        type = "supplier",
        phone = null,
        notes = null,
        createdAt = 100L,
        updatedAt = 100L,
        deletedAt = deletedAt,
        syncStatus = syncStatus,
        lastSyncAttemptAt = null,
        lastSyncError = lastSyncError,
    )

    private class FakeCounterpartyDao : LocalCounterpartyDao {
        private val rows = linkedMapOf<String, LocalCounterpartyEntity>()

        override suspend fun upsert(counterparty: LocalCounterpartyEntity) {
            rows[counterparty.id] = counterparty
        }

        override suspend fun upsertAll(counterparties: List<LocalCounterpartyEntity>) {
            counterparties.forEach { upsert(it) }
        }

        override suspend fun getById(id: String): LocalCounterpartyEntity? =
            rows[id]?.takeIf { it.deletedAt == null }

        override suspend fun getByIdIncludingDeleted(id: String): LocalCounterpartyEntity? =
            rows[id]

        override suspend fun deleteById(id: String) {
            rows.remove(id)
        }

        override suspend fun getRowsNeedingSync(businessId: String): List<LocalCounterpartyEntity> =
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

        override suspend fun getByBusinessAndSyncStatus(
            businessId: String,
            syncStatus: String,
        ): List<LocalCounterpartyEntity> = rows.values
            .filter { it.businessId == businessId && it.syncStatus.name == syncStatus && it.deletedAt == null }
            .sortedBy { it.createdAt }

        override fun observeByBusiness(businessId: String): Flow<List<LocalCounterpartyEntity>> =
            flowOf(rows.values.filter { it.businessId == businessId && it.deletedAt == null })

        override fun observeFiltered(
            businessId: String,
            search: String?,
            type: String?,
        ): Flow<List<LocalCounterpartyEntity>> = observeByBusiness(businessId)

        override fun observeById(id: String): Flow<LocalCounterpartyEntity?> = flowOf(rows[id])

        override fun observeRowsNeedingAttention(
            businessId: String,
        ): Flow<List<LocalCounterpartyEntity>> =
            flowOf(rows.values.filter { it.businessId == businessId && it.syncStatus != SyncStatus.SYNCED })

        override suspend fun clear() {
            rows.clear()
        }
    }

    private class FakeApiService(
        private val deleteCounterparty404: Boolean = false,
        private val createCounterpartyFailure: Throwable? = null,
        private val updateCounterpartyFailure: Throwable? = null,
    ) : ApiService {
        var deleteCounterpartyCalls = 0

        override suspend fun deleteCounterparty(businessId: String, counterpartyId: String) {
            deleteCounterpartyCalls += 1
            if (deleteCounterparty404) {
                throw HttpException(Response.error<Any>(404, "".toResponseBody(null)))
            }
        }

        override suspend fun health() = HealthResponse("ok")
        override suspend fun getMe(): ProfileDto = error("not used")
        override suspend fun updateMe(body: ProfileUpdateDto): ProfileDto = error("not used")
        override suspend fun listBusinesses(): List<BusinessDto> = emptyList()
        override suspend fun createBusiness(body: BusinessCreateDto): BusinessDto = error("not used")
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
        ): CounterpartyDto {
            createCounterpartyFailure?.let { throw it }
            return CounterpartyDto(
                id = body.id,
                businessId = businessId,
                name = body.name,
                normalizedName = body.name.trim().lowercase(),
                type = body.type,
                phone = body.phone,
                notes = body.notes,
                createdAt = "2026-05-29T00:00:00Z",
                updatedAt = "2026-05-29T00:00:00Z",
                deletedAt = null,
            )
        }
        override suspend fun getCounterparty(
            businessId: String,
            counterpartyId: String,
        ): CounterpartyDto = error("not used")
        override suspend fun updateCounterparty(
            businessId: String,
            counterpartyId: String,
            body: CounterpartyUpdateDto,
        ): CounterpartyDto {
            updateCounterpartyFailure?.let { throw it }
            return CounterpartyDto(
                id = counterpartyId,
                businessId = businessId,
                name = body.name ?: "Adewale Supplies",
                normalizedName = (body.name ?: "Adewale Supplies").trim().lowercase(),
                type = body.type ?: "supplier",
                phone = body.phone,
                notes = body.notes,
                createdAt = "2026-05-29T00:00:00Z",
                updatedAt = "2026-05-29T00:00:00Z",
                deletedAt = null,
            )
        }
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
