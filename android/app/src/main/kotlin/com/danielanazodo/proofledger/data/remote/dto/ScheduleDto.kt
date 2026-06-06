package com.danielanazodo.proofledger.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class InstallmentDto(
    val id: String,
    @Json(name = "business_id") val businessId: String,
    @Json(name = "schedule_version_id") val scheduleVersionId: String,
    val sequence: Int,
    @Json(name = "amount_minor") val amountMinor: Long,
    @Json(name = "due_date") val dueDate: String,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "deleted_at") val deletedAt: String?,
)

@JsonClass(generateAdapter = true)
data class ScheduleVersionDto(
    val id: String,
    @Json(name = "business_id") val businessId: String,
    @Json(name = "unit_id") val unitId: String,
    @Json(name = "version_no") val versionNo: Int,
    val source: String,
    @Json(name = "is_active") val isActive: Boolean,
    @Json(name = "offer_letter_screenshot_id") val offerLetterScreenshotId: String?,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "deleted_at") val deletedAt: String?,
    val installments: List<InstallmentDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class InstallmentInputDto(
    val id: String,
    val sequence: Int,
    @Json(name = "amount_minor") val amountMinor: Long,
    @Json(name = "due_date") val dueDate: String,
)

@JsonClass(generateAdapter = true)
data class ScheduleVersionCreateDto(
    val id: String,
    val source: String = "manual",
    @Json(name = "offer_letter_screenshot_id") val offerLetterScreenshotId: String? = null,
    val installments: List<InstallmentInputDto>,
)
