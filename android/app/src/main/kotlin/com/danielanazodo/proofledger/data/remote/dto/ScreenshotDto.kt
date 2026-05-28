package com.danielanazodo.proofledger.data.remote.dto

import com.squareup.moshi.Json

data class ScreenshotDto(
    val id: String,
    @Json(name = "business_id") val businessId: String,
    @Json(name = "transaction_id") val transactionId: String,
    @Json(name = "uploaded_by") val uploadedBy: String,
    @Json(name = "mime_type") val mimeType: String,
    @Json(name = "uploaded_file_size") val uploadedFileSize: Long,
    @Json(name = "original_file_size") val originalFileSize: Long,
    val width: Int?,
    val height: Int?,
    @Json(name = "storage_key") val storageKey: String,
    @Json(name = "storage_status") val storageStatus: String,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "deleted_at") val deletedAt: String?,
    /** Status of the server-side extraction job, populated on the upload
     *  response when Phase 8 is enabled. One of queued/rate_limited/null. */
    @Json(name = "draft_status") val draftStatus: String? = null,
)

data class SignedScreenshotUrlDto(
    val url: String,
    @Json(name = "expires_at") val expiresAt: String,
)
