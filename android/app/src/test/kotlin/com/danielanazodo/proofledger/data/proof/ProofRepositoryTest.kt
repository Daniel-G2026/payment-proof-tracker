package com.danielanazodo.proofledger.data.proof

import android.net.Uri
import com.danielanazodo.proofledger.data.local.ProofBackupStatus
import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.dao.LocalScreenshotDao
import com.danielanazodo.proofledger.data.local.dao.LocalSyncEventDao
import com.danielanazodo.proofledger.data.local.dao.LocalTransactionDao
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity
import com.danielanazodo.proofledger.data.local.entity.LocalScreenshotEntity
import com.danielanazodo.proofledger.data.local.entity.LocalSyncEventEntity
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
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
            val repository = repository(
                dao = dao,
                fileStore = fileStore,
                scheduler = scheduler,
                api = api,
                events = events,
            )

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

    @Test
    fun `refreshFromRemote restores linked proof as pending download and skips orphans`() =
        runBlocking {
            val dao = FakeScreenshotDao()
            val transactionDao = FakeTransactionDao().apply { seed(transaction("transaction-1")) }
            val api = FakeApiService(
                screenshots = listOf(
                    remoteScreenshot(id = "linked", transactionId = "transaction-1"),
                    remoteScreenshot(id = "orphan", transactionId = null),
                    remoteScreenshot(id = "missing-txn", transactionId = "transaction-404"),
                ),
            )
            val repository = repository(dao = dao, transactionDao = transactionDao, api = api)

            repository.refreshFromRemote("business-1")

            val restored = dao.getById("linked")!!
            assertEquals(ProofBackupStatus.PENDING_DOWNLOAD, restored.backupStatus)
            assertEquals("", restored.localOriginalPath)
            assertEquals("linked", restored.remoteScreenshotId)
            // Denormalized transaction fields are copied from the local txn.
            assertEquals(1200L, restored.transactionAmountMinor)
            assertEquals("Adewale Supplies", restored.counterpartyName)
            // Orphan (no txn) and unknown-transaction proofs are not restored.
            assertNull(dao.getById("orphan"))
            assertNull(dao.getById("missing-txn"))
        }

    @Test
    fun `refreshFromRemote leaves an already-present proof untouched`() = runBlocking {
        val dao = FakeScreenshotDao()
        val transactionDao = FakeTransactionDao().apply { seed(transaction("transaction-1")) }
        val existing = proof(id = "proof-1", backupStatus = ProofBackupStatus.BACKED_UP)
        dao.upsert(existing)
        val api = FakeApiService(
            screenshots = listOf(remoteScreenshot(id = "proof-1", transactionId = "transaction-1")),
        )
        val repository = repository(dao = dao, transactionDao = transactionDao, api = api)

        repository.refreshFromRemote("business-1")

        // Same row object: bytes/paths already on disk must never be clobbered.
        assertEquals(existing, dao.getById("proof-1"))
    }

    @Test
    fun `downloadAllProofsNow fetches bytes and marks the proof backed up`() = runBlocking {
        val dao = FakeScreenshotDao()
        val fileStore = FakeProofFileStore()
        val api = FakeApiService(downloadBytes = byteArrayOf(1, 2, 3))
        dao.upsert(proof(backupStatus = ProofBackupStatus.PENDING_DOWNLOAD))
        val repository = repository(dao = dao, fileStore = fileStore, api = api)

        val count = repository.downloadAllProofsNow("business-1")

        assertEquals(1, count)
        val row = dao.getById("proof-1")!!
        assertEquals(ProofBackupStatus.BACKED_UP, row.backupStatus)
        assertEquals("/tmp/restored/proof-1.jpg", row.localOriginalPath)
        assertEquals(listOf("proof-1"), fileStore.downloadedIds)
    }

    @Test
    fun `scheduleProofDownloadsOnWifi flips rows to wifi wait and enqueues wifi-only work`() =
        runBlocking {
            val dao = FakeScreenshotDao()
            val scheduler = FakeBackupWorkScheduler()
            dao.upsert(proof(backupStatus = ProofBackupStatus.PENDING_DOWNLOAD))
            val repository = repository(dao = dao, scheduler = scheduler)

            repository.scheduleProofDownloadsOnWifi("business-1")

            assertEquals(
                ProofBackupStatus.WAITING_FOR_WIFI_DOWNLOAD,
                dao.getById("proof-1")!!.backupStatus,
            )
            assertEquals(listOf("proof-1" to true), scheduler.enqueued)
        }

    private fun remoteScreenshot(
        id: String = "remote-proof-1",
        transactionId: String? = "transaction-1",
        deletedAt: String? = null,
    ): ScreenshotDto = ScreenshotDto(
        id = id,
        businessId = "business-1",
        transactionId = transactionId,
        uploadedBy = "owner-1",
        mimeType = "image/jpeg",
        uploadedFileSize = 100L,
        originalFileSize = 120L,
        width = 100,
        height = 200,
        storageKey = "business-1/$id.jpg",
        storageStatus = "stored",
        imageSha256 = "hash",
        createdAt = "2026-05-29T00:00:00Z",
        updatedAt = "2026-05-29T00:00:00Z",
        deletedAt = deletedAt,
        draftStatus = null,
    )

    private fun transaction(
        id: String = "transaction-1",
        businessId: String = "business-1",
    ): LocalTransactionEntity = LocalTransactionEntity(
        id = id,
        businessId = businessId,
        amountMinor = 1200L,
        currency = "NGN",
        direction = "money_in",
        transactionDate = "2026-05-28",
        counterpartyId = "cp-1",
        counterpartyName = "Adewale Supplies",
        category = "Materials",
        note = null,
        bankName = null,
        referenceNumber = null,
        source = "manual",
        status = "complete",
        createdAt = 100L,
        updatedAt = 100L,
        deletedAt = null,
        syncStatus = SyncStatus.SYNCED,
        lastSyncAttemptAt = null,
    )

    private fun repository(
        dao: FakeScreenshotDao = FakeScreenshotDao(),
        transactionDao: FakeTransactionDao = FakeTransactionDao(),
        fileStore: FakeProofFileStore = FakeProofFileStore(),
        scheduler: FakeBackupWorkScheduler = FakeBackupWorkScheduler(),
        api: FakeApiService = FakeApiService(),
        events: FakeSyncEventDao = FakeSyncEventDao(),
    ): ProofRepository = ProofRepository(
        screenshotDao = dao,
        transactionDao = transactionDao,
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

        override suspend fun findNeedingDownload(businessId: String): List<LocalScreenshotEntity> =
            rows.values.filter {
                it.businessId == businessId &&
                    it.backupStatus in setOf(
                        ProofBackupStatus.PENDING_DOWNLOAD,
                        ProofBackupStatus.WAITING_FOR_WIFI_DOWNLOAD,
                        ProofBackupStatus.DOWNLOAD_FAILED,
                    )
            }

        override fun observePendingDownloadCount(businessId: String): Flow<Int> =
            flowOf(
                rows.values.count {
                    it.businessId == businessId &&
                        it.backupStatus == ProofBackupStatus.PENDING_DOWNLOAD
                }
            )

        override suspend fun deleteById(id: String) {
            rows.remove(id)
        }

        override suspend fun clear() {
            rows.clear()
        }
    }

    private class FakeTransactionDao : LocalTransactionDao {
        private val rows = linkedMapOf<String, LocalTransactionEntity>()

        fun seed(transaction: LocalTransactionEntity) {
            rows[transaction.id] = transaction
        }

        override suspend fun getById(id: String): LocalTransactionEntity? = rows[id]

        override suspend fun upsert(transaction: LocalTransactionEntity) {
            rows[transaction.id] = transaction
        }

        override suspend fun upsertAll(transactions: List<LocalTransactionEntity>) {
            transactions.forEach { rows[it.id] = it }
        }

        override suspend fun getByIdIncludingDeleted(id: String): LocalTransactionEntity? = rows[id]

        override suspend fun deleteById(id: String) {
            rows.remove(id)
        }

        override suspend fun clear() {
            rows.clear()
        }

        override suspend fun getRowsNeedingSync(businessId: String): List<LocalTransactionEntity> =
            error("not used")

        override suspend fun getByBusinessAndSyncStatus(
            businessId: String,
            syncStatus: String,
        ): List<LocalTransactionEntity> = error("not used")

        override fun observeByBusiness(businessId: String): Flow<List<LocalTransactionEntity>> =
            error("not used")

        override fun observeFiltered(
            businessId: String,
            direction: String?,
            search: String?,
        ): Flow<List<LocalTransactionEntity>> = error("not used")

        override fun observeByCounterparty(
            businessId: String,
            counterpartyId: String,
        ): Flow<List<LocalTransactionEntity>> = error("not used")

        override fun observeById(id: String): Flow<LocalTransactionEntity?> = error("not used")

        override fun observeRowsNeedingAttention(
            businessId: String,
        ): Flow<List<LocalTransactionEntity>> = error("not used")

        override suspend fun findDuplicateCandidates(
            businessId: String,
            amountMinor: Long,
            direction: String,
            transactionDate: String,
            referenceNumber: String?,
            excludeTransactionId: String?,
        ): List<LocalTransactionEntity> = error("not used")
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
        val downloadedIds = mutableListOf<String>()

        override fun prepare(uri: Uri, id: String): PreparedProofFile = error("not used")

        override fun deleteForId(id: String) {
            deletedIds += id
        }

        override fun sha256(uri: Uri): String = error("not used")

        override fun writeDownloaded(id: String, bytes: ByteArray, mimeType: String): String {
            downloadedIds += id
            return "/tmp/restored/$id.jpg"
        }
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
        private val screenshots: List<ScreenshotDto> = emptyList(),
        private val signedUrl: String = "https://signed.example/proof",
        private val downloadBytes: ByteArray = ByteArray(0),
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
        override suspend fun listScreenshots(
            businessId: String,
            limit: Int,
            offset: Int,
        ): List<ScreenshotDto> = if (offset == 0) screenshots else emptyList()

        override suspend fun downloadProofBytes(url: String): ResponseBody =
            downloadBytes.toResponseBody()

        override suspend fun getScreenshotSignedUrl(screenshotId: String): SignedScreenshotUrlDto =
            SignedScreenshotUrlDto(url = signedUrl, expiresAt = "2026-05-29T00:00:00Z")
        override suspend fun getScreenshotDraft(screenshotId: String): TransactionDraftDto =
            error("not used")
        override suspend fun confirmDraft(
            draftId: String,
            body: TransactionDraftConfirmDto,
        ): TransactionDraftDto = error("not used")
    }
}
