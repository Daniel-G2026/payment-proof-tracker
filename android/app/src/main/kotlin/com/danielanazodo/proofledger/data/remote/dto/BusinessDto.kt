package com.danielanazodo.proofledger.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BusinessDto(
    val id: String,
    @Json(name = "owner_id") val ownerId: String,
    val name: String,
    val country: String,
    val currency: String,
    @Json(name = "business_type") val businessType: String?,
    val plan: String,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String,
)

@JsonClass(generateAdapter = true)
data class BusinessCreateDto(
    val id: String,
    val name: String,
    val country: String = "Nigeria",
    val currency: String = "NGN",
    @Json(name = "business_type") val businessType: String? = null,
)
