package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.dao.LocalCounterpartyDao
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The managed list of person *types* (customer, supplier, subcontractor,
 * buyer…) a business offers when adding/editing people. Stored as a small
 * per-business list in the key-value metadata table — types are picker
 * metadata, not financial data, so they don't need their own synced table.
 *
 * Robust to a fresh device: the list **re-seeds from the types already on the
 * business's people** (which *do* sync), so real types survive reinstall even
 * though the curated list itself is on-device. Counterparties keep their `type`
 * as a string snapshot, so deleting a type never breaks existing people.
 */
@Singleton
class CounterpartyTypeRepository @Inject constructor(
    private val metadataDao: LocalAppMetadataDao,
    private val counterpartyDao: LocalCounterpartyDao,
) {
    private fun key(businessId: String) = "$KEY_PREFIX$businessId"

    fun observeTypes(businessId: String): Flow<List<String>> =
        metadataDao.observe(key(businessId)).map { parse(it?.value) }

    /** Seed the list the first time it's used: built-in defaults plus any types
     * already in use by this business's people. No-op once a list exists. */
    suspend fun seedIfNeeded(businessId: String) {
        if (metadataDao.get(key(businessId)) != null) return
        val fromPeople = counterpartyDao.getDistinctTypes(businessId).mapNotNull { normalize(it) }
        write(businessId, (BUILT_IN_TYPES + fromPeople).distinct())
    }

    suspend fun addType(businessId: String, rawType: String) {
        val type = normalize(rawType) ?: return
        val current = parse(metadataDao.get(key(businessId))?.value).ifEmpty { BUILT_IN_TYPES }
        if (type in current) return
        write(businessId, current + type)
    }

    suspend fun removeType(businessId: String, type: String) {
        val current = parse(metadataDao.get(key(businessId))?.value)
        if (type !in current) return
        write(businessId, current - type)
    }

    private suspend fun write(businessId: String, types: List<String>) {
        metadataDao.upsert(
            LocalAppMetadataEntity(
                key = key(businessId),
                value = types.joinToString("\n"),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun parse(value: String?): List<String> =
        value?.split("\n")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

    /** Normalize to the snake_case key form used everywhere a `type` is stored. */
    private fun normalize(value: String): String? =
        value.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { null }

    companion object {
        private const val KEY_PREFIX = "counterparty_types:"
        val BUILT_IN_TYPES = listOf("customer", "supplier", "subcontractor", "employee")
    }
}
