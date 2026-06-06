package com.danielanazodo.proofledger.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CounterpartyTypeDto(
    val id: String,
    @Json(name = "business_id") val businessId: String,
    val name: String,
    @Json(name = "normalized_name") val normalizedName: String,
    @Json(name = "is_archived") val isArchived: Boolean,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "deleted_at") val deletedAt: String?,
)

@JsonClass(generateAdapter = true)
data class CounterpartyTypeCreateDto(
    val id: String,
    val name: String,
)

@JsonClass(generateAdapter = true)
data class CounterpartyTypeUpdateDto(
    val name: String? = null,
    @Json(name = "is_archived") val isArchived: Boolean? = null,
    /** Optimistic concurrency — see TransactionUpdateDto.expectedUpdatedAt. */
    @Json(name = "expected_updated_at") val expectedUpdatedAt: String? = null,
)
