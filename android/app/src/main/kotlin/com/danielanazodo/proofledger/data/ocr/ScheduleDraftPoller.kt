package com.danielanazodo.proofledger.data.ocr

import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.DraftErrorCode
import com.danielanazodo.proofledger.data.remote.dto.DraftStatus
import com.danielanazodo.proofledger.data.remote.dto.ScheduleDraftDto
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import retrofit2.HttpException

/**
 * Offer-letter sibling of [ServerDraftPoller]. Polls the server for a single
 * offer-letter screenshot's schedule draft and emits [ScheduleDraftState]s
 * until a terminal one (Ready / Failed) or the polling budget runs out.
 *
 * 404 means "draft row not created yet" → keep polling. Network/unexpected
 * errors stop immediately so the screen can drop the scanning state and fall
 * back to manual entry.
 */
class ScheduleDraftPoller @Inject constructor(
    private val api: ApiService,
) {
    fun poll(
        screenshotId: String,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): Flow<ScheduleDraftState> = flow {
        emit(ScheduleDraftState.Processing)
        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            when (val result = fetch(screenshotId)) {
                ScheduleFetchResult.NotReady -> {
                    // Keep polling.
                }
                is ScheduleFetchResult.Error -> {
                    emit(ScheduleDraftState.Failed(errorCode = result.errorCode, message = result.message))
                    return@flow
                }
                is ScheduleFetchResult.Draft -> {
                    val dto = result.dto
                    when (dto.status) {
                        DraftStatus.READY -> {
                            emit(ScheduleDraftState.Ready(dto))
                            return@flow
                        }
                        DraftStatus.FAILED -> {
                            emit(
                                ScheduleDraftState.Failed(
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
        emit(ScheduleDraftState.Failed(errorCode = DraftErrorCode.TIMEOUT, message = null))
    }

    private suspend fun fetch(screenshotId: String): ScheduleFetchResult = try {
        ScheduleFetchResult.Draft(api.getScheduleDraft(screenshotId))
    } catch (e: HttpException) {
        if (e.code() == 404) {
            ScheduleFetchResult.NotReady
        } else {
            ScheduleFetchResult.Error(
                errorCode = DraftErrorCode.NETWORK,
                message = "Couldn't reach the draft service.",
            )
        }
    } catch (_: Exception) {
        ScheduleFetchResult.Error(
            errorCode = DraftErrorCode.NETWORK,
            message = "Couldn't reach the draft service.",
        )
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L
        const val DEFAULT_MAX_ATTEMPTS = 12
    }
}

private sealed interface ScheduleFetchResult {
    data object NotReady : ScheduleFetchResult
    data class Draft(val dto: ScheduleDraftDto) : ScheduleFetchResult
    data class Error(val errorCode: String, val message: String?) : ScheduleFetchResult
}

sealed interface ScheduleDraftState {
    data object Processing : ScheduleDraftState
    data class Ready(val draft: ScheduleDraftDto) : ScheduleDraftState
    data class Failed(val errorCode: String, val message: String?) : ScheduleDraftState
}
