package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.dao.LocalCounterpartyDao
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

class CounterpartyTypeRepositoryTest {
    private val businessId = "business-1"

    private fun repo(
        distinctTypes: List<String> = emptyList(),
        metadata: FakeMetadataDao = FakeMetadataDao(),
    ): Pair<CounterpartyTypeRepository, FakeMetadataDao> {
        val cpDao = object : LocalCounterpartyDao by Mockito.mock(LocalCounterpartyDao::class.java) {
            override suspend fun getDistinctTypes(businessId: String): List<String> = distinctTypes
        }
        return CounterpartyTypeRepository(metadata, cpDao) to metadata
    }

    @Test
    fun `seedIfNeeded seeds built-ins plus distinct types from people`() = runBlocking {
        val (repo, _) = repo(distinctTypes = listOf("buyer", "customer", "Land Owner"))
        repo.seedIfNeeded(businessId)
        val types = repo.observeTypes(businessId).first()
        // built-ins first (customer dedups), then the new ones from people, normalized.
        assertEquals(
            listOf("customer", "supplier", "subcontractor", "employee", "buyer", "land_owner"),
            types,
        )
    }

    @Test
    fun `seedIfNeeded is a no-op once a list exists`() = runBlocking {
        val (repo, meta) = repo(distinctTypes = listOf("buyer"))
        repo.seedIfNeeded(businessId)
        repo.removeType(businessId, "supplier")
        repo.seedIfNeeded(businessId) // must not re-add supplier
        assertEquals(false, repo.observeTypes(businessId).first().contains("supplier"))
        assertEquals(1, meta.store.size)
    }

    @Test
    fun `addType normalizes, appends, and dedupes`() = runBlocking {
        val (repo, _) = repo()
        repo.seedIfNeeded(businessId)
        repo.addType(businessId, "  Land Owner ")
        repo.addType(businessId, "land_owner") // duplicate after normalize
        val types = repo.observeTypes(businessId).first()
        assertEquals(1, types.count { it == "land_owner" })
        assertEquals("land_owner", types.last())
    }

    @Test
    fun `removeType removes from the list`() = runBlocking {
        val (repo, _) = repo()
        repo.seedIfNeeded(businessId)
        repo.removeType(businessId, "employee")
        assertEquals(false, repo.observeTypes(businessId).first().contains("employee"))
    }

    private class FakeMetadataDao : LocalAppMetadataDao {
        val store = mutableMapOf<String, LocalAppMetadataEntity>()
        private val flows = mutableMapOf<String, MutableStateFlow<LocalAppMetadataEntity?>>()

        override suspend fun upsert(entry: LocalAppMetadataEntity) {
            store[entry.key] = entry
            flows.getOrPut(entry.key) { MutableStateFlow(null) }.value = entry
        }

        override suspend fun get(key: String): LocalAppMetadataEntity? = store[key]

        override fun observe(key: String): Flow<LocalAppMetadataEntity?> =
            flows.getOrPut(key) { MutableStateFlow(store[key]) }
    }
}
