package com.danielanazodo.proofledger.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Server-side AI extraction result for one offer-letter screenshot (Phase 13
 * Cut 6). Mirrors the backend `ScheduleDraftOut`. `installments` is null when
 * the rows couldn't be read with confidence — the review screen then opens an
 * empty (manual) editor. Reuses the same `DraftStatus`/`DraftErrorCode`
 * vocabulary as the payment-proof draft.
 */
@JsonClass(generateAdapter = true)
data class ScheduleDraftDto(
    val id: String,
    @Json(name = "screenshot_id") val screenshotId: String,
    @Json(name = "business_id") val businessId: String,
    val status: String,
    @Json(name = "buyer_name") val buyerName: String?,
    @Json(name = "total_minor") val totalMinor: Long?,
    @Json(name = "currency_code") val currencyCode: String?,
    val installments: List<ScheduleDraftInstallmentDto>?,
    val confidence: Map<String, Double>?,
    @Json(name = "error_code") val errorCode: String?,
    @Json(name = "error_message") val errorMessage: String?,
    @Json(name = "model_version") val modelVersion: String?,
    val attempts: Int,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "confirmed_at") val confirmedAt: String?,
)

@JsonClass(generateAdapter = true)
data class ScheduleDraftInstallmentDto(
    val sequence: Int,
    @Json(name = "amount_minor") val amountMinor: Long,
    @Json(name = "due_date") val dueDate: String,
)

/**
 * Phase 13 — advisory offer-letter duplicate check. The scanned letter's
 * extracted fields (buyer + the installment plan) and the proof image id are
 * cross-referenced against schedules already entered in the business.
 * `excludeUnitId` is the unit being confirmed, so its own prior version (a
 * renegotiation) isn't flagged.
 */
@JsonClass(generateAdapter = true)
data class ScheduleDuplicateRequestDto(
    @Json(name = "buyer_name") val buyerName: String?,
    val installments: List<ScheduleDuplicateInstallmentDto>,
    @Json(name = "screenshot_id") val screenshotId: String? = null,
    @Json(name = "exclude_unit_id") val excludeUnitId: String? = null,
)

@JsonClass(generateAdapter = true)
data class ScheduleDuplicateInstallmentDto(
    @Json(name = "amount_minor") val amountMinor: Long,
    @Json(name = "due_date") val dueDate: String,
)

@JsonClass(generateAdapter = true)
data class ScheduleDuplicateCandidateDto(
    @Json(name = "unit_id") val unitId: String,
    @Json(name = "project_id") val projectId: String,
    @Json(name = "unit_label") val unitLabel: String,
    @Json(name = "buyer_name") val buyerName: String?,
    @Json(name = "schedule_version_id") val scheduleVersionId: String,
    @Json(name = "version_no") val versionNo: Int,
    @Json(name = "installment_count") val installmentCount: Int,
    @Json(name = "total_minor") val totalMinor: Long,
    val reasons: List<String>,
    val score: Int,
)
