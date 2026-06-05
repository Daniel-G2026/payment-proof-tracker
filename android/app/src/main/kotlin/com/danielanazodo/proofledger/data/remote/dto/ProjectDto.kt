package com.danielanazodo.proofledger.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ProjectDto(
    val id: String,
    @Json(name = "business_id") val businessId: String,
    val name: String,
    val note: String?,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "deleted_at") val deletedAt: String?,
)

@JsonClass(generateAdapter = true)
data class ProjectCreateDto(
    val id: String,
    val name: String,
    val note: String? = null,
)

@JsonClass(generateAdapter = true)
data class ProjectUpdateDto(
    val name: String? = null,
    val note: String? = null,
    /** Optimistic concurrency — see TransactionUpdateDto.expectedUpdatedAt. */
    @Json(name = "expected_updated_at") val expectedUpdatedAt: String? = null,
)
