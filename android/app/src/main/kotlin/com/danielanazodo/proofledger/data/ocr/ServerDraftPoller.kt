package com.danielanazodo.proofledger.data.ocr

import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.DraftErrorCode
import com.danielanazodo.proofledger.data.remote.dto.DraftStatus
import com.danielanazodo.proofledger.data.remote.dto.TransactionDraftDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Polls the server for a single screenshot's extraction draft and emits a
 * sequence of [DraftState]s until a terminal one (Ready / Failed) or until
 * the polling budget runs out.
 *
 * Pure-Kotlin so the ViewModel can integrate via a simple Flow collect, and
 * so unit tests don't need WorkManager / Android plumbing.
 *
 * Polling contract:
 *  - 404 from the server means "draft row not created yet" — keep polling.
 *  - status == queued / processing / rate_limited → emit Processing, keep polling.
 *  - status == ready → emit Ready(draft), stop.
 *  - status == failed → emit Failed(errorCode), stop.
 *  - Hit `maxAttempts` without a terminal state → emit Failed(TIMEOUT), stop.
 *  - Network / unexpected error → log inside Failed, stop (no further polling
 *    — server is probably down and we'd just waste retries on the form).
 */
class ServerDraftPoller @Inject constructor(
    private val api: ApiService,
) {
    /**
     * Poll until terminal or budget exhausted.
     *
     * @param screenshotId server-side screenshot UUID (i.e. the remote id, not
     *   the local row id).
     * @param intervalMs delay between polls. 5000 (5s) matches the plan.
     * @param maxAttempts hard cap. 12 attempts × 5s = 60s window.
     */
    fun poll(
        screenshotId: String,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): Flow<DraftState> = flow {
        emit(DraftState.Processing)
        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            val dto = fetchOrNull(screenshotId)
            if (dto != null) {
                when (dto.status) {
                    DraftStatus.READY -> {
                        emit(DraftState.Ready(dto))
                        return@flow
                    }
                    DraftStatus.FAILED -> {
                        emit(
                            DraftState.Failed(
                                errorCode = dto.errorCode ?: DraftErrorCode.VISION,
                                message = dto.errorMessage,
                            )
                        )
                        return@flow
                    }
                    // queued / processing / rate_limited → fall through, keep polling.
                    else -> { /* keep polling */ }
                }
            }
            // Don't sleep after the final attempt — falls through to timeout below.
            if (attempt < maxAttempts) {
                delay(intervalMs)
            }
        }
        emit(DraftState.Failed(errorCode = DraftErrorCode.TIMEOUT, message = null))
    }

    private suspend fun fetchOrNull(screenshotId: String): TransactionDraftDto? = try {
        api.getScreenshotDraft(screenshotId)
    } catch (e: HttpException) {
        // 404 is the expected "not yet" answer — keep polling. Other status
        // codes are unusual; surface as null too and let the timeout handle
        // it. Treating 401/403 as "stop immediately" would be safer but the
        // ViewModel doesn't model auth errors for this path yet.
        if (e.code() == 404) null else null
    } catch (_: Exception) {
        // Network blip / parsing error — null this attempt, keep polling.
        null
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L
        const val DEFAULT_MAX_ATTEMPTS = 12
    }
}

/**
 * Polling result emitted by [ServerDraftPoller].
 *
 * Processing covers "queued, being worked, or response not yet available."
 * Ready carries the full DTO so the caller can apply field-ownership rules
 * before merging. Failed carries the error code so the UI can pick the right
 * copy ("Couldn't read this one" vs "Couldn't reach the server").
 */
sealed interface DraftState {
    data object Processing : DraftState
    data class Ready(val draft: TransactionDraftDto) : DraftState
    data class Failed(val errorCode: String, val message: String?) : DraftState
}

/**
 * Convert a server-side draft into the existing [OcrDraft] shape the
 * Phase 7 form-merge logic already consumes. Keeps the integration seam
 * tiny: ViewModel still calls applyDraftIfFieldsBlank(draft).
 */
fun TransactionDraftDto.toOcrDraft(): OcrDraft = OcrDraft(
    amountMinor = amountMinor,
    dateIso = transactionDate,
    reference = referenceNumber,
    bankName = bankName,
    rawText = "", // The on-device OCR text is what the form already has; the
                  // server-side raw response isn't needed for form display.
)
