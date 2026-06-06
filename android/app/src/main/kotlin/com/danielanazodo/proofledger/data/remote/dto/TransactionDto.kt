package com.danielanazodo.proofledger.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TransactionDto(
    val id: String,
    @Json(name = "business_id") val businessId: String,
    @Json(name = "amount_minor") val amountMinor: Long,
    val currency: String,
    val direction: String,
    @Json(name = "transaction_date") val transactionDate: String,
    @Json(name = "counterparty_id") val counterpartyId: String?,
    @Json(name = "counterparty_name") val counterpartyName: String?,
    val category: String?,
    @Json(name = "project_id") val projectId: String? = null,
    @Json(name = "unit_id") val unitId: String? = null,
    @Json(name = "earmarked_installment_id") val earmarkedInstallmentId: String? = null,
    val note: String?,
    @Json(name = "bank_name") val bankName: String?,
    @Json(name = "reference_number") val referenceNumber: String?,
    val source: String,
    val status: String,
    @Json(name = "created_by") val createdBy: String,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "deleted_at") val deletedAt: String?,
)

@JsonClass(generateAdapter = true)
data class TransactionCreateDto(
    val id: String,
    @Json(name = "amount_minor") val amountMinor: Long,
    val currency: String = "NGN",
    val direction: String,
    @Json(name = "transaction_date") val transactionDate: String,
    @Json(name = "counterparty_id") val counterpartyId: String? = null,
    @Json(name = "counterparty_name") val counterpartyName: String? = null,
    val category: String? = null,
    /** Phase 13: money-in unit link + earmark (Cut 3); money-out project (Cut 5). */
    @Json(name = "project_id") val projectId: String? = null,
    @Json(name = "unit_id") val unitId: String? = null,
    @Json(name = "earmarked_installment_id") val earmarkedInstallmentId: String? = null,
    val note: String? = null,
    @Json(name = "bank_name") val bankName: String? = null,
    @Json(name = "reference_number") val referenceNumber: String? = null,
    /** Phase 8.1: id of an orphan screenshot uploaded eagerly during this
     *  form session. Backend links the screenshot to this transaction
     *  atomically in the same DB commit as the insert. */
    @Json(name = "screenshot_id") val screenshotId: String? = null,
)

@JsonClass(generateAdapter = true)
data class TransactionUpdateDto(
    @Json(name = "amount_minor") val amountMinor: Long? = null,
    val currency: String? = null,
    val direction: String? = null,
    @Json(name = "transaction_date") val transactionDate: String? = null,
    @Json(name = "counterparty_id") val counterpartyId: String? = null,
    @Json(name = "counterparty_name") val counterpartyName: String? = null,
    val category: String? = null,
    /** Phase 13: money-in unit link + earmark (Cut 3); money-out project (Cut 5). */
    @Json(name = "project_id") val projectId: String? = null,
    @Json(name = "unit_id") val unitId: String? = null,
    @Json(name = "earmarked_installment_id") val earmarkedInstallmentId: String? = null,
    val note: String? = null,
    @Json(name = "bank_name") val bankName: String? = null,
    @Json(name = "reference_number") val referenceNumber: String? = null,
    /** Phase 8.1: link/replace the screenshot on an edit. Same semantics as
     *  on create — see TransactionCreateDto.screenshotId. */
    @Json(name = "screenshot_id") val screenshotId: String? = null,
    /** Optimistic concurrency: `updated_at` the client saw when it opened the
     *  record. Backend returns 409 if the row has been modified since. */
    @Json(name = "expected_updated_at") val expectedUpdatedAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class DuplicateCandidateRequestDto(
    @Json(name = "amount_minor") val amountMinor: Long,
    val direction: String,
    @Json(name = "transaction_date") val transactionDate: String,
    @Json(name = "counterparty_id") val counterpartyId: String? = null,
    @Json(name = "counterparty_name") val counterpartyName: String? = null,
    @Json(name = "reference_number") val referenceNumber: String? = null,
    @Json(name = "screenshot_id") val screenshotId: String? = null,
    @Json(name = "exclude_transaction_id") val excludeTransactionId: String? = null,
)

@JsonClass(generateAdapter = true)
data class DuplicateCandidateDto(
    val transaction: TransactionDto,
    val reasons: List<String>,
    val score: Int,
)
