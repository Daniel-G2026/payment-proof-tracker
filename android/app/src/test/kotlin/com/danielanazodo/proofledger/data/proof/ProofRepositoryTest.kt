package com.danielanazodo.proofledger.data.proof

import android.net.Uri
import com.danielanazodo.proofledger.data.local.ProofBackupStatus
import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.dao.LocalScreenshotDao
import com.danielanazodo.proofledger.data.local.dao.LocalSyncEventDao
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity
import com.danielanazodo.proofledger.data.local.entity.LocalScreenshotEntity
import com.danielanazodo.proofledger.data.local.entity.LocalSyncEventEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.HealthResponse
import com.danielanazodo.proofledger.data.remote.dto.BusinessCreateDto
import com.danielanazodo.proofledger.data.remote.dto.BusinessDto
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
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ProofRepositoryTest {
    @Test
    fun `uploaded proof delete keeps hidden tombstone and queues retry when remote cleanup fails`() =
        runBlocking {
            val dao = FakeScreenshotDao()
            val fileStore = FakeProofFileStore()
            val scheduler = FakeBackupWorkScheduler()
            val events = FakeSyncEventDao()
            val api = FakeApiService(deleteFailure = RuntimeException("offline"))
            val row = proof(backupStatus = ProofBackupStatus.BACKED_UP)
            dao.upsert(row)
            val repository = repository(dao, fileStore, scheduler, api, events)

            repository.deleteProof(row.id)

            val tombstone = dao.getById(row.id)!!
            assertEquals(ProofBackupStatus.PENDING_REMOTE_DELETE, tombstone.backupStatus)
            assertEquals(listOf(row.id), fileStore.deletedIds)
            assertEquals(listOf(row.id to false), scheduler.enqueued)
            assertEquals(listOf(row.remoteScreenshotId), api.deletedScreenshotIds)
            assertEquals(emptyList<LocalScreenshotEntity>(), dao.visibleForBusiness(row.businessId))
            assertEquals("remote_delete", events.rows.single().action)
            assertEquals("failure", events.rows.single().status)
        }

    @Test
    fun `pending remote delete retry removes row after server cleanup succeeds`() = runBlocking {
        val dao = FakeScreenshotDao()
        val api = FakeApiService()
        val row = proof(backupStatus = ProofBackupStatus.PENDING_REMOTE_DELETE)
        dao.upsert(row)
        val repository = repository(dao = dao, api = api)

        repository.upload(row.id)

        assertNull(dao.getById(row.id))
        assertEquals(listOf(row.remoteScreenshotId), api.deletedScreenshotIds)
    }

    @Test
    fun `pending remote delete retry treats backend 404 as terminal success`() = runBlocking {
        val dao = FakeScreenshotDao()
        val api = FakeApiService(deleteFailure = http404())
        val row = proof(backupStatus = ProofBackupStatus.PENDING_REMOTE_DELETE)
        dao.upsert(row)
        val repository = repository(dao = dao, api = api)

        repository.upload(row.id)

        assertNull(dao.getById(row.id))
        assertEquals(listOf(row.remoteScreenshotId), api.deletedScreenshotIds)
    }

    @Test
    fun `backup failure writes local sync event`() = runBlocking {
        val dao = FakeScreenshotDao()
        val events = FakeSyncEventDao()
        val row = proof(backupStatus = ProofBackupStatus.PENDING_BACKUP)
        dao.upsert(row)
        val repository = repository(
            dao = dao,
            api = FakeApiService(uploadFailure = RuntimeException("offline")),
            events = events,
        )

        runCatching { repository.upload(row.id) }

        val event = events.rows.single()
        assertEquals("proof", event.entityType)
        assertEquals(row.id, event.entityId)
        assertEquals("backup", event.action)
        assertEquals("failure", event.status)
    }

    private fun repository(
        dao: FakeScreenshotDao = FakeScreenshotDao(),
        fileStore: FakeProofFileStore = FakeProofFileStore(),
        scheduler: FakeBackupWorkScheduler = FakeBackupWorkScheduler(),
        api: FakeApiService = FakeApiService(),
        events: FakeSyncEventDao = FakeSyncEventDao(),
    ): ProofRepository = ProofRepository(
        screenshotDao = dao,
        metadataDao = FakeMetadataDao(),
        fileStore = fileStore,
        scheduler = scheduler,
        api = api,
        syncEventLogger = SyncEventLogger(events),
    )

    private fun proof(
        id: String = "proof-1",
        backupStatus: ProofBackupStatus,
        remoteScreenshotId: String? = "remote-proof-1",
    ): LocalScreenshotEntity = LocalScreenshotEntity(
        id = id,
        businessId = "business-1",
        transactionId = "transaction-1",
        localOriginalPath = "/tmp/proof-1.jpg",
        localUploadPath = null,
        originalFileSizeBytes = 100L,
        uploadFileSizeBytes = null,
        mimeType = "image/jpeg",
        width = 100,
        height = 200,
        transactionAmountMinor = 1200L,
        transactionDate = "2026-05-28",
        transactionDirection = "money_in",
        counterpartyId = null,
        counterpartyName = "Adewale Supplies",
        isImportant = false,
        backupStatus = backupStatus,
        remoteScreenshotId = remoteScreenshotId,
        remoteStorageKey = remoteScreenshotId?.let { "business-1/$it.jpg" },
        imageSha256 = "hash",
        createdAt = 100L,
        updatedAt = 100L,
        lastBackupAttemptAt = null,
        lastBackupError = null,
    )

    private fun http404(): HttpException =
        HttpException(Response.error<Any>(404, "".toResponseBody(null)))

    private class FakeScreenshotDao : LocalScreenshotDao {
        private val rows = linkedMapOf<String, LocalScreenshotEntity>()

        override suspend fun upsert(row: LocalScreenshotEntity) {
            rows[row.id] = row
        }

        override suspend fun getById(id: String): LocalScreenshotEntity? = rows[id]

        override fun observeById(id: String): Flow<LocalScreenshotEntity?> = flowOf(rows[id])

        override suspend fun findStuckBackingUp(): List<LocalScreenshotEntity> =
            rows.values.filter { it.backupStatus == ProofBackupStatus.BACKING_UP }

        override suspend fun findByTransactionId(transactionId: String): List<LocalScreenshotEntity> =
            rows.values.filter { it.transactionId == transactionId }

        override suspend fun findByImageSha256(
            businessId: String,
            imageSha256: String,
        ): List<LocalScreenshotEntity> =
            rows.values.filter { it.businessId == businessId && it.imageSha256 == imageSha256 }

        override suspend fun findMissingImageSha256(businessId: String): List<LocalScreenshotEntity> =
            rows.values.filter { it.businessId == businessId && it.imageSha256 == null }

        override suspend fun findAll(): List<LocalScreenshotEntity> = rows.values.toList()

        override fun observeLatestForTransaction(transactionId: String): Flow<LocalScreenshotEntity?> =
            flowOf(
                rows.values
                    .filter {
                        it.transactionId == transactionId &&
                            it.backupStatus != ProofBackupStatus.PENDING_REMOTE_DELETE
                    }
                    .maxByOrNull { it.createdAt }
            )

        override fun observeByBusiness(businessId: String): Flow<List<LocalScreenshotEntity>> =
            flowOf(visibleForBusiness(businessId))

        override fun observeRowsNeedingAttention(
            businessId: String,
        ): Flow<List<LocalScreenshotEntity>> =
            flowOf(rows.values.filter { it.businessId == businessId && it.backupStatus != ProofBackupStatus.BACKED_UP })

        fun visibleForBusiness(businessId: String): List<LocalScreenshotEntity> =
            rows.values.filter {
                it.businessId == businessId &&
                    it.backupStatus != ProofBackupStatus.PENDING_REMOTE_DELETE
            }

        override suspend fun pendingForManualBackup(
            businessId: String,
            statuses: List<ProofBackupStatus>,
            importantOnly: Boolean,
            direction: String?,
            counterpartyId: String?,
            counterpartySearch: String?,
            startDate: String?,
            endDate: String?,
            minAmountMinor: Long?,
            maxAmountMinor: Long?,
        ): List<LocalScreenshotEntity> =
            rows.values.filter { it.businessId == businessId && it.backupStatus in statuses }

        override suspend fun deleteById(id: String) {
            rows.remove(id)
        }

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

        override fun observeFailures(): Flow<List<LocalSyncEventEntity>> =
            flowOf(rows.filter { it.status == "failure" })

        override suspend fun clear() {
            rows.clear()
        }
    }

    private class FakeProofFileStore : ProofFileStore {
        val deletedIds = mutableListOf<String>()

        override fun prepare(uri: Uri, id: String): PreparedProofFile = error("not used")

        override fun deleteForId(id: String) {
            deletedIds += id
        }

        override fun sha256(uri: Uri): String = error("not used")
    }

    private class FakeBackupWorkScheduler : BackupWorkScheduler {
        val enqueued = mutableListOf<Pair<String, Boolean>>()

        override fun enqueue(proofId: String, wifiOnly: Boolean) {
            enqueued += proofId to wifiOnly
        }
    }

    private class FakeApiService(
        private val deleteFailure: Throwable? = null,
        private val uploadFailure: Throwable? = null,
    ) : ApiService {
        val deletedScreenshotIds = mutableListOf<String>()

        override suspend fun deleteScreenshot(screenshotId: String) {
            deletedScreenshotIds += screenshotId
            deleteFailure?.let { throw it }
        }

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
        ): ScreenshotDto {
            uploadFailure?.let { throw it }
            return ScreenshotDto(
                id = "remote-proof-1",
                businessId = businessId,
                transactionId = "transaction-1",
                uploadedBy = "owner-1",
                mimeType = "image/jpeg",
                uploadedFileSize = 100L,
                originalFileSize = 100L,
                width = 100,
                height = 200,
                storageKey = "business-1/remote-proof-1.jpg",
                storageStatus = "stored",
                imageSha256 = "hash",
                createdAt = "2026-05-29T00:00:00Z",
                updatedAt = "2026-05-29T00:00:00Z",
                deletedAt = null,
                draftStatus = null,
            )
        }
        override suspend fun uploadOrphanScreenshot(
            businessId: String,
            id: RequestBody,
            originalFileSize: RequestBody,
            width: RequestBody,
            height: RequestBody,
            file: MultipartBody.Part,
        ): ScreenshotDto = error("not used")
        override suspend fun exportPdf(
            businessId: String,
            startDate: String,
            endDate: String,
        ): okhttp3.ResponseBody = error("not used")
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
