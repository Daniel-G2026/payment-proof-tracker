package com.danielanazodo.proofledger.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CounterpartyDto(
    val id: String,
    @Json(name = "business_id") val businessId: String,
    val name: String,
    @Json(name = "normalized_name") val normalizedName: String,
    val type: String,
    val phone: String?,
    val notes: String?,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "deleted_at") val deletedAt: String?,
)

@JsonClass(generateAdapter = true)
data class CounterpartyCreateDto(
    val id: String,
    val name: String,
    val type: String,
    val phone: String? = null,
    val notes: String? = null,
)

@JsonClass(generateAdapter = true)
data class CounterpartyUpdateDto(
    val name: String? = null,
    val type: String? = null,
    val phone: String? = null,
    val notes: String? = null,
)

@JsonClass(generateAdapter = true)
data class CounterpartySummaryDto(
    @Json(name = "total_in") val totalIn: Long,
    @Json(name = "total_out") val totalOut: Long,
    @Json(name = "last_transaction_date") val lastTransactionDate: String?,
    @Json(name = "transaction_count") val transactionCount: Int,
)
