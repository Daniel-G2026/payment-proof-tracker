package com.danielanazodo.proofledger.data.ocr

import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.DraftErrorCode
import com.danielanazodo.proofledger.data.remote.dto.DraftStatus
import com.danielanazodo.proofledger.data.remote.dto.TransactionDraftDto
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import retrofit2.HttpException

/**
 * Polls the server for a single screenshot's extraction draft and emits a
 * sequence of [DraftState]s until a terminal one (Ready / Failed) or until
 * the polling budget runs out.
 *
 * 404 means "draft row not created yet", so polling continues. Network and
 * unexpected errors stop immediately so the form can leave the drafting state
 * and show a useful offline/server message.
 */
class ServerDraftPoller @Inject constructor(
    private val api: ApiService,
) {
    fun poll(
        screenshotId: String,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): Flow<DraftState> = flow {
        emit(DraftState.Processing)
        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            when (val result = fetch(screenshotId)) {
                DraftFetchResult.NotReady -> {
                    // Keep polling.
                }
                is DraftFetchResult.Error -> {
                    emit(DraftState.Failed(errorCode = result.errorCode, message = result.message))
                    return@flow
                }
                is DraftFetchResult.Draft -> {
                    val dto = result.dto
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
                        else -> {
                            // queued / processing / rate_limited: keep polling.
                        }
                    }
                }
            }
            if (attempt < maxAttempts) {
                delay(intervalMs)
            }
        }
        emit(DraftState.Failed(errorCode = DraftErrorCode.TIMEOUT, message = null))
    }

    private suspend fun fetch(screenshotId: String): DraftFetchResult = try {
        DraftFetchResult.Draft(api.getScreenshotDraft(screenshotId))
    } catch (e: HttpException) {
        if (e.code() == 404) {
            DraftFetchResult.NotReady
        } else {
            DraftFetchResult.Error(
                errorCode = DraftErrorCode.NETWORK,
                message = "Couldn't reach the draft service.",
            )
        }
    } catch (_: Exception) {
        DraftFetchResult.Error(
            errorCode = DraftErrorCode.NETWORK,
            message = "Couldn't reach the draft service.",
        )
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L
        const val DEFAULT_MAX_ATTEMPTS = 12
    }
}

private sealed interface DraftFetchResult {
    data object NotReady : DraftFetchResult
    data class Draft(val dto: TransactionDraftDto) : DraftFetchResult
    data class Error(val errorCode: String, val message: String?) : DraftFetchResult
}

sealed interface DraftState {
    data object Processing : DraftState
    data class Ready(val draft: TransactionDraftDto) : DraftState
    data class Failed(val errorCode: String, val message: String?) : DraftState
}

fun TransactionDraftDto.toOcrDraft(): OcrDraft = OcrDraft(
    amountMinor = amountMinor,
    dateIso = transactionDate,
    reference = referenceNumber,
    bankName = bankName,
    rawText = "",
)
