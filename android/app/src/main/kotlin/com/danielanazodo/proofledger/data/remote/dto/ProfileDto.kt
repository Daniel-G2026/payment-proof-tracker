package com.danielanazodo.proofledger.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ProfileDto(
    val id: String,
    @Json(name = "full_name") val fullName: String?,
    val phone: String?,
    val email: String?,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String,
)

@JsonClass(generateAdapter = true)
data class ProfileUpdateDto(
    @Json(name = "full_name") val fullName: String? = null,
    val phone: String? = null,
)
