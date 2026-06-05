package com.danielanazodo.proofledger.data.remote.dto

import com.squareup.moshi.Json

/**
 * One recorded change to an entity (Phase 12 edit history). `oldValues` /
 * `newValues` carry the field-level diff; values are loosely typed because the
 * server stores a JSON snapshot (numbers arrive as Double via Moshi).
 */
data class AuditEventDto(
    val id: String,
    @Json(name = "business_id") val businessId: String,
    @Json(name = "actor_id") val actorId: String,
    @Json(name = "entity_type") val entityType: String,
    @Json(name = "entity_id") val entityId: String?,
    val action: String,
    val status: String,
    @Json(name = "old_values") val oldValues: Map<String, @JvmSuppressWildcards Any?>?,
    @Json(name = "new_values") val newValues: Map<String, @JvmSuppressWildcards Any?>?,
    @Json(name = "created_at") val createdAt: String,
)
