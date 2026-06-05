package com.danielanazodo.proofledger.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class UnitDto(
    val id: String,
    @Json(name = "business_id") val businessId: String,
    @Json(name = "project_id") val projectId: String,
    val label: String,
    @Json(name = "buyer_counterparty_id") val buyerCounterpartyId: String?,
    @Json(name = "total_price_minor") val totalPriceMinor: Long?,
    val currency: String,
    val status: String,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "deleted_at") val deletedAt: String?,
)

@JsonClass(generateAdapter = true)
data class UnitCreateDto(
    val id: String,
    @Json(name = "project_id") val projectId: String,
    val label: String,
    @Json(name = "buyer_counterparty_id") val buyerCounterpartyId: String? = null,
    @Json(name = "total_price_minor") val totalPriceMinor: Long? = null,
    val currency: String = "NGN",
    val status: String? = null,
)

@JsonClass(generateAdapter = true)
data class UnitUpdateDto(
    val label: String? = null,
    @Json(name = "buyer_counterparty_id") val buyerCounterpartyId: String? = null,
    @Json(name = "total_price_minor") val totalPriceMinor: Long? = null,
    val currency: String? = null,
    val status: String? = null,
    /** Optimistic concurrency — see TransactionUpdateDto.expectedUpdatedAt. */
    @Json(name = "expected_updated_at") val expectedUpdatedAt: String? = null,
)
