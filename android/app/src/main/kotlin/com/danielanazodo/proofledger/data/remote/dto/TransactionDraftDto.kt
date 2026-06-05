package com.danielanazodo.proofledger.data.remote.dto

import com.squareup.moshi.Json

/**
 * Server-side OCR/AI extraction result for a screenshot. Polled from
 * GET /screenshots/{id}/draft. The cleaned per-field values live alongside a
 * per-field `confidence` map (0-1) so the client can highlight low-confidence
 * fields if desired. Fields below the server-side confidence floor are
 * already nulled out by the worker — the client just trusts what it sees.
 */
data class TransactionDraftDto(
    val id: String,
    @Json(name = "screenshot_id") val screenshotId: String,
    @Json(name = "business_id") val businessId: String,
    val status: String,
    @Json(name = "amount_minor") val amountMinor: Long?,
    @Json(name = "currency_code") val currencyCode: String?,
    @Json(name = "transaction_date") val transactionDate: String?,
    @Json(name = "reference_number") val referenceNumber: String?,
    @Json(name = "bank_name") val bankName: String?,
    @Json(name = "counterparty_name") val counterpartyName: String?,
    val confidence: Map<String, Double>?,
    @Json(name = "error_code") val errorCode: String?,
    @Json(name = "error_message") val errorMessage: String?,
    @Json(name = "model_version") val modelVersion: String?,
    val attempts: Int,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "confirmed_at") val confirmedAt: String?,
)

/** Telemetry payload — POST /transaction-drafts/{id}/confirm. */
data class TransactionDraftConfirmDto(
    @Json(name = "transaction_id") val transactionId: String,
)

object DraftStatus {
    const val QUEUED = "queued"
    const val PROCESSING = "processing"
    const val READY = "ready"
    const val FAILED = "failed"
    const val RATE_LIMITED = "rate_limited"
}

object DraftErrorCode {
    const val VISION = "vision_error"
    const val PARSE = "parse_error"
    const val TIMEOUT = "timeout"
    const val UNREADABLE = "unreadable"
    const val NETWORK = "network_error"
}
