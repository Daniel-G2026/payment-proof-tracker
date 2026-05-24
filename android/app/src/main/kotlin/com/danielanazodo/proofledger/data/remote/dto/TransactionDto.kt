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
    @Json(name = "counterparty_name") val counterpartyName: String?,
    val category: String?,
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
    @Json(name = "counterparty_name") val counterpartyName: String? = null,
    val category: String? = null,
    val note: String? = null,
    @Json(name = "bank_name") val bankName: String? = null,
    @Json(name = "reference_number") val referenceNumber: String? = null,
)

@JsonClass(generateAdapter = true)
data class TransactionUpdateDto(
    @Json(name = "amount_minor") val amountMinor: Long? = null,
    val currency: String? = null,
    val direction: String? = null,
    @Json(name = "transaction_date") val transactionDate: String? = null,
    @Json(name = "counterparty_name") val counterpartyName: String? = null,
    val category: String? = null,
    val note: String? = null,
    @Json(name = "bank_name") val bankName: String? = null,
    @Json(name = "reference_number") val referenceNumber: String? = null,
)
