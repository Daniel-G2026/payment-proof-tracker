package com.danielanazodo.proofledger.data.ocr

import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.HealthResponse
import com.danielanazodo.proofledger.data.remote.dto.BusinessCreateDto
import com.danielanazodo.proofledger.data.remote.dto.BusinessDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyCreateDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartySummaryDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyUpdateDto
import com.danielanazodo.proofledger.data.remote.dto.DraftErrorCode
import com.danielanazodo.proofledger.data.remote.dto.DraftStatus
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Tests run in real time with a 5ms polling interval so they stay fast and
 * don't require kotlinx-coroutines-test. The Flow contract is what we care
 * about — exact wall-clock timing isn't.
 */
class ServerDraftPollerTest {

    private val pollIntervalMs = 5L
    private val testScreenshotId = "9a1b8b73-1f2f-4c8c-8e6e-46e35bbcd7b1"

    @Test
    fun `emits Processing then Ready when draft eventually returns ready`() {
        val responses = listOf(
            FakeResponse.NotFound,
            FakeResponse.Status(DraftStatus.QUEUED),
            FakeResponse.Status(DraftStatus.PROCESSING),
            FakeResponse.Status(DraftStatus.READY, amountMinor = 850_000),
        )
        val api = ScriptedApiService(responses)
        val poller = ServerDraftPoller(api)

        val emitted = runBlocking { poller.poll(testScreenshotId, pollIntervalMs, 10).toList() }

        assertEquals(2, emitted.size)
        assertTrue(emitted[0] is DraftState.Processing)
        val ready = emitted[1] as DraftState.Ready
        assertEquals(850_000L, ready.draft.amountMinor)
        assertEquals(DraftStatus.READY, ready.draft.status)
    }

    @Test
    fun `emits Failed with unreadable error code when worker fails terminally`() {
        val responses = listOf(
            FakeResponse.Status(DraftStatus.PROCESSING),
            FakeResponse.Status(
                DraftStatus.FAILED,
                errorCode = DraftErrorCode.UNREADABLE,
                errorMessage = "Model reported image was not a payment proof.",
            ),
        )
        val poller = ServerDraftPoller(ScriptedApiService(responses))

        val emitted = runBlocking { poller.poll(testScreenshotId, pollIntervalMs, 10).toList() }

        val failed = emitted.last() as DraftState.Failed
        assertEquals(DraftErrorCode.UNREADABLE, failed.errorCode)
        assertTrue(failed.message!!.contains("not a payment proof"))
    }

    @Test
    fun `emits Failed with timeout when budget exhausted`() {
        // Always processing — never reaches a terminal state.
        val responses = List(20) { FakeResponse.Status(DraftStatus.PROCESSING) }
        val poller = ServerDraftPoller(ScriptedApiService(responses))

        val emitted = runBlocking { poller.poll(testScreenshotId, pollIntervalMs, 4).toList() }

        val failed = emitted.last() as DraftState.Failed
        assertEquals(DraftErrorCode.TIMEOUT, failed.errorCode)
    }

    @Test
    fun `404 keeps polling until ready arrives`() {
        val responses = listOf(
            FakeResponse.NotFound,
            FakeResponse.NotFound,
            FakeResponse.NotFound,
            FakeResponse.Status(DraftStatus.READY, amountMinor = 100),
        )
        val poller = ServerDraftPoller(ScriptedApiService(responses))

        val emitted = runBlocking { poller.poll(testScreenshotId, pollIntervalMs, 8).toList() }

        assertTrue(emitted.first() is DraftState.Processing)
        val ready = emitted.last() as DraftState.Ready
        assertEquals(100L, ready.draft.amountMinor)
    }

    @Test
    fun `rate_limited status keeps polling without erroring`() {
        // Rate-limited at upload-time still produces a draft row (no) — but if
        // the server briefly reports rate_limited we shouldn't bail; just wait.
        // In the real product this is unlikely on the poll endpoint, but the
        // poller is the one place we can defensively swallow it.
        val responses = listOf(
            FakeResponse.Status(DraftStatus.RATE_LIMITED),
            FakeResponse.Status(DraftStatus.READY, amountMinor = 42),
        )
        val poller = ServerDraftPoller(ScriptedApiService(responses))

        val emitted = runBlocking { poller.poll(testScreenshotId, pollIntervalMs, 5).toList() }

        assertTrue(emitted.last() is DraftState.Ready)
    }

    @Test
    fun `toOcrDraft maps server fields onto the existing draft shape`() {
        val dto = makeDraftDto(
            status = DraftStatus.READY,
            amountMinor = 850_000,
            transactionDate = "2026-05-24",
            referenceNumber = "TRF240524ABC",
            bankName = "GTBank",
        )
        val mapped = dto.toOcrDraft()
        assertEquals(850_000L, mapped.amountMinor)
        assertEquals("2026-05-24", mapped.dateIso)
        assertEquals("TRF240524ABC", mapped.reference)
        assertEquals("GTBank", mapped.bankName)
    }

    // --- fakes -----------------------------------------------------------

    /**
     * Sequence of canned responses. The poller pulls one per attempt; if it
     * polls beyond the script length, the last entry repeats (so timeout
     * tests can declare a single "always processing" entry without padding
     * a list of 12).
     */
    private class ScriptedApiService(
        private val script: List<FakeResponse>,
    ) : ApiService {
        private var index = 0
        override suspend fun getScreenshotDraft(screenshotId: String): TransactionDraftDto {
            val response = script.getOrNull(index) ?: script.last()
            index++
            return when (response) {
                is FakeResponse.NotFound -> throw HttpException(
                    Response.error<Any>(404, "".toResponseBody(null))
                )
                is FakeResponse.Status -> makeDraftDto(
                    status = response.status,
                    amountMinor = response.amountMinor,
                    errorCode = response.errorCode,
                    errorMessage = response.errorMessage,
                )
            }
        }

        // --- noop stubs for the rest of the interface ---------------------
        override suspend fun health() = HealthResponse("ok")
        override suspend fun getMe(): ProfileDto = error("not used")
        override suspend fun updateMe(body: ProfileUpdateDto): ProfileDto = error("not used")
        override suspend fun listBusinesses(): List<BusinessDto> = emptyList()
        override suspend fun createBusiness(body: BusinessCreateDto): BusinessDto = error("not used")
        override suspend fun getBusiness(id: String): BusinessDto = error("not used")
        override suspend fun listCounterparties(
            businessId: String, search: String?, type: String?
        ): List<CounterpartyDto> = emptyList()
        override suspend fun createCounterparty(
            businessId: String, body: CounterpartyCreateDto
        ): CounterpartyDto = error("not used")
        override suspend fun getCounterparty(
            businessId: String, counterpartyId: String
        ): CounterpartyDto = error("not used")
        override suspend fun updateCounterparty(
            businessId: String, counterpartyId: String, body: CounterpartyUpdateDto
        ): CounterpartyDto = error("not used")
        override suspend fun deleteCounterparty(businessId: String, counterpartyId: String) {}
        override suspend fun getCounterpartySummary(
            businessId: String, counterpartyId: String
        ): CounterpartySummaryDto = error("not used")
        override suspend fun listTransactions(
            businessId: String, direction: String?, search: String?, startDate: String?, endDate: String?
        ): List<TransactionDto> = emptyList()
        override suspend fun createTransaction(
            businessId: String, body: TransactionCreateDto
        ): TransactionDto = error("not used")
        override suspend fun duplicateCandidates(
            businessId: String, body: DuplicateCandidateRequestDto
        ): List<DuplicateCandidateDto> = emptyList()
        override suspend fun getTransaction(
            businessId: String, transactionId: String
        ): TransactionDto = error("not used")
        override suspend fun updateTransaction(
            businessId: String, transactionId: String, body: TransactionUpdateDto
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
        override suspend fun getScreenshotSignedUrl(
            screenshotId: String
        ): SignedScreenshotUrlDto = error("not used")
        override suspend fun confirmDraft(
            draftId: String, body: TransactionDraftConfirmDto
        ): TransactionDraftDto = error("not used")
    }

    private sealed interface FakeResponse {
        data object NotFound : FakeResponse
        data class Status(
            val status: String,
            val amountMinor: Long? = null,
            val errorCode: String? = null,
            val errorMessage: String? = null,
        ) : FakeResponse
    }
}

// File-level helper so both the test class and the mapper test can use it.
private fun makeDraftDto(
    status: String,
    amountMinor: Long? = null,
    transactionDate: String? = null,
    referenceNumber: String? = null,
    bankName: String? = null,
    errorCode: String? = null,
    errorMessage: String? = null,
): TransactionDraftDto = TransactionDraftDto(
    id = "00000000-0000-0000-0000-000000000001",
    screenshotId = "00000000-0000-0000-0000-000000000002",
    businessId = "00000000-0000-0000-0000-000000000003",
    status = status,
    amountMinor = amountMinor,
    currencyCode = null,
    transactionDate = transactionDate,
    referenceNumber = referenceNumber,
    bankName = bankName,
    counterpartyName = null,
    confidence = null,
    errorCode = errorCode,
    errorMessage = errorMessage,
    modelVersion = null,
    attempts = 1,
    createdAt = "2026-05-24T00:00:00Z",
    updatedAt = "2026-05-24T00:00:00Z",
    confirmedAt = null,
)
