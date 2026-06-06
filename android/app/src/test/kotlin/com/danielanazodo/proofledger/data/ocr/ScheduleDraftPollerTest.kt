package com.danielanazodo.proofledger.data.ocr

import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.DraftErrorCode
import com.danielanazodo.proofledger.data.remote.dto.DraftStatus
import com.danielanazodo.proofledger.data.remote.dto.ScheduleDraftDto
import com.danielanazodo.proofledger.data.remote.dto.ScheduleDraftInstallmentDto
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import retrofit2.HttpException
import retrofit2.Response

/**
 * Real-time tests with a 5ms interval, like ServerDraftPollerTest. The Flow
 * contract is what matters, not wall-clock timing.
 */
class ScheduleDraftPollerTest {
    private val intervalMs = 5L
    private val screenshotId = "9a1b8b73-1f2f-4c8c-8e6e-46e35bbcd7b1"

    @Test
    fun `emits Processing then Ready when draft eventually returns ready`() {
        val poller = ScheduleDraftPoller(
            ScriptedApi(
                listOf(
                    Resp.NotFound,
                    Resp.Status(DraftStatus.QUEUED),
                    Resp.Status(DraftStatus.PROCESSING),
                    Resp.Status(
                        DraftStatus.READY,
                        installments = listOf(
                            ScheduleDraftInstallmentDto(0, 200_000, "2026-07-01"),
                            ScheduleDraftInstallmentDto(1, 100_000, "2026-08-01"),
                        ),
                    ),
                ),
            ),
        )
        val emitted = runBlocking { poller.poll(screenshotId, intervalMs, 10).toList() }
        assertEquals(2, emitted.size)
        assertTrue(emitted[0] is ScheduleDraftState.Processing)
        val ready = emitted[1] as ScheduleDraftState.Ready
        assertEquals(2, ready.draft.installments!!.size)
        assertEquals(DraftStatus.READY, ready.draft.status)
    }

    @Test
    fun `emits Failed with unreadable error code when worker fails terminally`() {
        val poller = ScheduleDraftPoller(
            ScriptedApi(
                listOf(
                    Resp.Status(DraftStatus.PROCESSING),
                    Resp.Status(
                        DraftStatus.FAILED,
                        errorCode = DraftErrorCode.UNREADABLE,
                        errorMessage = "Model reported image was not an offer letter.",
                    ),
                ),
            ),
        )
        val emitted = runBlocking { poller.poll(screenshotId, intervalMs, 10).toList() }
        val failed = emitted.last() as ScheduleDraftState.Failed
        assertEquals(DraftErrorCode.UNREADABLE, failed.errorCode)
        assertTrue(failed.message!!.contains("offer letter"))
    }

    @Test
    fun `emits Failed with timeout when budget exhausted`() {
        val poller = ScheduleDraftPoller(
            ScriptedApi(List(20) { Resp.Status(DraftStatus.PROCESSING) }),
        )
        val emitted = runBlocking { poller.poll(screenshotId, intervalMs, 4).toList() }
        assertEquals(DraftErrorCode.TIMEOUT, (emitted.last() as ScheduleDraftState.Failed).errorCode)
    }

    @Test
    fun `404 keeps polling until ready arrives`() {
        val poller = ScheduleDraftPoller(
            ScriptedApi(
                listOf(
                    Resp.NotFound,
                    Resp.NotFound,
                    Resp.Status(DraftStatus.READY, installments = emptyList()),
                ),
            ),
        )
        val emitted = runBlocking { poller.poll(screenshotId, intervalMs, 8).toList() }
        assertTrue(emitted.first() is ScheduleDraftState.Processing)
        assertTrue(emitted.last() is ScheduleDraftState.Ready)
    }

    @Test
    fun `null installments still produce a Ready draft`() {
        val poller = ScheduleDraftPoller(
            ScriptedApi(listOf(Resp.Status(DraftStatus.READY, installments = null, buyerName = "Ada"))),
        )
        val emitted = runBlocking { poller.poll(screenshotId, intervalMs, 4).toList() }
        val ready = emitted.last() as ScheduleDraftState.Ready
        assertEquals("Ada", ready.draft.buyerName)
        assertEquals(null, ready.draft.installments)
    }

    @Test
    fun `network error fails immediately`() {
        val poller = ScheduleDraftPoller(ScriptedApi(listOf(Resp.NetworkError)))
        val emitted = runBlocking { poller.poll(screenshotId, intervalMs, 8).toList() }
        assertEquals(2, emitted.size)
        assertEquals(DraftErrorCode.NETWORK, (emitted.last() as ScheduleDraftState.Failed).errorCode)
    }

    @Test
    fun `rate_limited status keeps polling without erroring`() {
        val poller = ScheduleDraftPoller(
            ScriptedApi(
                listOf(
                    Resp.Status(DraftStatus.RATE_LIMITED),
                    Resp.Status(DraftStatus.READY, installments = emptyList()),
                ),
            ),
        )
        val emitted = runBlocking { poller.poll(screenshotId, intervalMs, 5).toList() }
        assertTrue(emitted.last() is ScheduleDraftState.Ready)
    }

    // --- fakes -----------------------------------------------------------

    private sealed interface Resp {
        data object NetworkError : Resp
        data object NotFound : Resp
        data class Status(
            val status: String,
            val installments: List<ScheduleDraftInstallmentDto>? = null,
            val buyerName: String? = null,
            val errorCode: String? = null,
            val errorMessage: String? = null,
        ) : Resp
    }

    /** ApiService via delegation to a Mockito mock, overriding only the
     * schedule-draft poll endpoint. */
    private class ScriptedApi(private val script: List<Resp>) :
        ApiService by Mockito.mock(ApiService::class.java) {
        private var index = 0
        override suspend fun getScheduleDraft(screenshotId: String): ScheduleDraftDto {
            val resp = script.getOrNull(index) ?: script.last()
            index++
            return when (resp) {
                is Resp.NetworkError -> error("network down")
                is Resp.NotFound -> throw HttpException(Response.error<Any>(404, "".toResponseBody(null)))
                is Resp.Status -> ScheduleDraftDto(
                    id = "00000000-0000-0000-0000-000000000001",
                    screenshotId = "00000000-0000-0000-0000-000000000002",
                    businessId = "00000000-0000-0000-0000-000000000003",
                    status = resp.status,
                    buyerName = resp.buyerName,
                    totalMinor = null,
                    currencyCode = null,
                    installments = resp.installments,
                    confidence = null,
                    errorCode = resp.errorCode,
                    errorMessage = resp.errorMessage,
                    modelVersion = null,
                    attempts = 1,
                    createdAt = "2026-06-06T00:00:00Z",
                    updatedAt = "2026-06-06T00:00:00Z",
                    confirmedAt = null,
                )
            }
        }
    }
}
