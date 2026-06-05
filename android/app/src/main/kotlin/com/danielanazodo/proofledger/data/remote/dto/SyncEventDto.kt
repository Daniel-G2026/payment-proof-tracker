package com.danielanazodo.proofledger.data.remote.dto

import com.squareup.moshi.Json

/** A device-reported sync attempt flushed to the backend sync_events table. */
data class SyncEventReportDto(
    val id: String,
    @Json(name = "entity_type") val entityType: String,
    @Json(name = "entity_id") val entityId: String?,
    val action: String,
    val status: String,
    @Json(name = "error_message") val errorMessage: String?,
    @Json(name = "occurred_at") val occurredAt: String,
)

data class SyncEventIngestResultDto(
    val accepted: Int,
    val duplicates: Int,
)
