package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.dao.LocalCounterpartyDao
import com.danielanazodo.proofledger.data.local.dao.LocalCounterpartyTypeDao
import com.danielanazodo.proofledger.data.local.dao.LocalSyncEventDao
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyTypeEntity
import com.danielanazodo.proofledger.data.local.entity.LocalSyncEventEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyTypeCreateDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyTypeDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyTypeUpdateDto
import com.danielanazodo.proofledger.data.sync.SyncEventLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import retrofit2.HttpException
import retrofit2.Response

class CounterpartyTypeRepositoryTest {
    private val businessId = "biz-1"

    private fun build(
        api: FakeApi = FakeApi(),
        dao: FakeTypeDao = FakeTypeDao(),
        distinctTypes: List<String> = emptyList(),
        metadata: FakeMetadataDao = FakeMetadataDao(),
        events: FakeSyncEventDao = FakeSyncEventDao(),
    ): CounterpartyTypeRepository {
        val cpDao = object : LocalCounterpartyDao by Mockito.mock(LocalCounterpartyDao::class.java) {
            override suspend fun getDistinctTypes(businessId: String): List<String> = distinctTypes
        }
        return CounterpartyTypeRepository(api.service, dao, cpDao, metadata, SyncEventLogger(events))
    }

    @Test
    fun `addType syncs immediately and marks the row SYNCED`() = runBlocking {
        val dao = FakeTypeDao()
        val api = FakeApi()
        val events = FakeSyncEventDao()
        val repo = build(api = api, dao = dao, events = events)

        repo.addType(businessId, "  Land Owner ")

        val row = dao.rows.values.single()
        assertEquals("land_owner", row.normalizedName)
        assertEquals(SyncStatus.SYNCED, row.syncStatus)
        assertEquals(1, api.createCalls)
        val event = events.rows.single()
        assertEquals("counterparty_type", event.entityType)
        assertEquals("create", event.action)
        assertEquals("success", event.status)
        assertEquals(listOf("land_owner"), repo.observeTypes(businessId).first())
    }

    @Test
    fun `addType offline keeps the row FAILED and logs failure`() = runBlocking {
        val dao = FakeTypeDao()
        val api = FakeApi(createFailure = RuntimeException("offline"))
        val events = FakeSyncEventDao()
        val repo = build(api = api, dao = dao, events = events)

        repo.addType(businessId, "buyer")

        assertEquals(SyncStatus.FAILED, dao.rows.values.single().syncStatus)
        assertEquals("failure", events.rows.single().status)
    }

    @Test
    fun `addType dedupes by normalized key`() = runBlocking {
        val dao = FakeTypeDao()
        val api = FakeApi()
        val repo = build(api = api, dao = dao)

        repo.addType(businessId, "Land Owner")
        repo.addType(businessId, "  land_owner ") // same after normalize

        assertEquals(1, dao.rows.values.count { it.deletedAt == null })
        assertEquals(1, api.createCalls)
    }

    @Test
    fun `refreshFromRemote stores server types as SYNCED and observeTypes returns keys`() =
        runBlocking {
            val dao = FakeTypeDao()
            val api = FakeApi(
                remote = listOf(
                    typeDto(id = "r1", name = "customer"),
                    typeDto(id = "r2", name = "supplier"),
                ),
            )
            val repo = build(api = api, dao = dao)

            repo.refreshFromRemote(businessId)

            assertEquals(setOf("customer", "supplier"), repo.observeTypes(businessId).first().toSet())
            assertTrue(dao.rows.values.all { it.syncStatus == SyncStatus.SYNCED })
        }

    @Test
    fun `syncPending drains a pending upload created offline`() = runBlocking {
        val dao = FakeTypeDao()
        val api = FakeApi()
        dao.upsert(type(syncStatus = SyncStatus.PENDING_UPLOAD))
        val repo = build(api = api, dao = dao)

        repo.syncPending(businessId)

        assertEquals(SyncStatus.SYNCED, dao.getByIdIncludingDeleted("type-1")!!.syncStatus)
        assertEquals(1, api.createCalls)
    }

    @Test
    fun `stale pending update becomes conflict instead of retry failure`() = runBlocking {
        val dao = FakeTypeDao()
        val api = FakeApi(updateFailure = http(409))
        val events = FakeSyncEventDao()
        dao.upsert(type(syncStatus = SyncStatus.PENDING_UPDATE))
        val repo = build(api = api, dao = dao, events = events)

        repo.syncPending(businessId)

        assertEquals(SyncStatus.CONFLICT, dao.getByIdIncludingDeleted("type-1")!!.syncStatus)
        assertEquals("failure", events.rows.single().status)
    }

    @Test
    fun `removeType of a never-synced row removes it locally without a backend call`() =
        runBlocking {
            val dao = FakeTypeDao()
            val api = FakeApi()
            dao.upsert(type(syncStatus = SyncStatus.PENDING_UPLOAD))
            val repo = build(api = api, dao = dao)

            repo.removeType(businessId, "land_owner")

            assertNull(dao.getByIdIncludingDeleted("type-1"))
            assertEquals(0, api.deleteCalls)
        }

    @Test
    fun `removeType of a synced row (e g a default) soft-deletes via backend`() = runBlocking {
        val dao = FakeTypeDao()
        val api = FakeApi()
        val events = FakeSyncEventDao()
        dao.upsert(type(syncStatus = SyncStatus.SYNCED))
        val repo = build(api = api, dao = dao, events = events)

        repo.removeType(businessId, "land_owner")

        val updated = dao.getByIdIncludingDeleted("type-1")!!
        assertEquals(SyncStatus.SYNCED, updated.syncStatus)
        assertEquals(1, api.deleteCalls)
        assertEquals("delete", events.rows.single().action)
        assertFalse(repo.observeTypes(businessId).first().contains("land_owner"))
    }

    @Test
    fun `pending delete treats backend 404 as terminal success`() = runBlocking {
        val dao = FakeTypeDao()
        val api = FakeApi(delete404 = true)
        dao.upsert(
            type(syncStatus = SyncStatus.PENDING_DELETE, deletedAt = 123L, lastSyncError = "prev"),
        )
        val repo = build(api = api, dao = dao)

        repo.syncPending(businessId)

        val updated = dao.getByIdIncludingDeleted("type-1")!!
        assertEquals(SyncStatus.SYNCED, updated.syncStatus)
        assertNull(updated.lastSyncError)
        assertEquals(1, api.deleteCalls)
    }

    @Test
    fun `seedIfNeeded seeds built-ins plus legacy and people types once`() = runBlocking {
        val dao = FakeTypeDao()
        val metadata = FakeMetadataDao()
        // A legacy on-device-only type plus a type already on a person.
        metadata.upsert(
            LocalAppMetadataEntity("counterparty_types:$businessId", "investor", 1L),
        )
        val repo = build(dao = dao, distinctTypes = listOf("land_owner"), metadata = metadata)

        repo.seedIfNeeded(businessId)

        val seeded = repo.observeTypes(businessId).first().toSet()
        assertEquals(
            setOf("customer", "supplier", "subcontractor", "employee", "investor", "land_owner"),
            seeded,
        )
        // Marker written; a second call must not re-seed a removed default.
        repo.removeType(businessId, "supplier")
        repo.seedIfNeeded(businessId)
        assertFalse(repo.observeTypes(businessId).first().contains("supplier"))
    }

    @Test
    fun `seedIfNeeded imports with backend-matching deterministic ids`() = runBlocking {
        val dao = FakeTypeDao()
        val repo = build(dao = dao)

        repo.seedIfNeeded(businessId)

        val customer = dao.rows.values.single { it.normalizedName == "customer" }
        // uuid5(7c9e6a41-…, "biz-1:customer") computed by the backend's helper.
        assertEquals("5a4592fe-7bfb-5542-850e-858b90fb5d32", customer.id)
        assertEquals(
            "5a4592fe-7bfb-5542-850e-858b90fb5d32",
            CounterpartyTypeRepository.seedTypeId(businessId, "customer"),
        )
    }

    private fun http(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "".toResponseBody(null)))

    private fun type(
        id: String = "type-1",
        syncStatus: SyncStatus,
        deletedAt: Long? = null,
        lastSyncError: String? = null,
    ): LocalCounterpartyTypeEntity = LocalCounterpartyTypeEntity(
        id = id,
        businessId = businessId,
        name = "land_owner",
        normalizedName = "land_owner",
        isArchived = false,
        createdAt = 100L,
        updatedAt = 100L,
        deletedAt = deletedAt,
        syncStatus = syncStatus,
        lastSyncAttemptAt = null,
        lastSyncError = lastSyncError,
    )

    private fun typeDto(id: String, name: String): CounterpartyTypeDto = CounterpartyTypeDto(
        id = id,
        businessId = businessId,
        name = name,
        normalizedName = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'),
        isArchived = false,
        createdAt = "2026-06-01T00:00:00Z",
        updatedAt = "2026-06-01T00:00:00Z",
        deletedAt = null,
    )

    /** ApiService via interface delegation to a Mockito mock, overriding only
     * the counterparty-type endpoints — avoids hand-stubbing every method. */
    private class FakeApi(
        private val remote: List<CounterpartyTypeDto> = emptyList(),
        private val createFailure: Throwable? = null,
        private val updateFailure: Throwable? = null,
        private val delete404: Boolean = false,
    ) {
        var createCalls = 0
        var deleteCalls = 0
        private val base = Mockito.mock(ApiService::class.java)
        val service: ApiService = object : ApiService by base {
            override suspend fun listCounterpartyTypes(
                businessId: String,
                search: String?,
            ): List<CounterpartyTypeDto> = remote

            override suspend fun createCounterpartyType(
                businessId: String,
                body: CounterpartyTypeCreateDto,
            ): CounterpartyTypeDto {
                createCalls += 1
                createFailure?.let { throw it }
                return CounterpartyTypeDto(
                    id = body.id,
                    businessId = businessId,
                    name = body.name,
                    normalizedName = body.name.trim().lowercase()
                        .replace(Regex("[^a-z0-9]+"), "_").trim('_'),
                    isArchived = false,
                    createdAt = "2026-06-01T00:00:00Z",
                    updatedAt = "2026-06-01T00:00:00Z",
                    deletedAt = null,
                )
            }

            override suspend fun updateCounterpartyType(
                businessId: String,
                typeId: String,
                body: CounterpartyTypeUpdateDto,
            ): CounterpartyTypeDto {
                updateFailure?.let { throw it }
                return CounterpartyTypeDto(
                    id = typeId,
                    businessId = businessId,
                    name = body.name ?: "land_owner",
                    normalizedName = body.name ?: "land_owner",
                    isArchived = body.isArchived ?: false,
                    createdAt = "2026-06-01T00:00:00Z",
                    updatedAt = "2026-06-01T00:00:00Z",
                    deletedAt = null,
                )
            }

            override suspend fun deleteCounterpartyType(businessId: String, typeId: String) {
                deleteCalls += 1
                if (delete404) throw HttpException(Response.error<Any>(404, "".toResponseBody(null)))
            }
        }
    }

    private class FakeTypeDao : LocalCounterpartyTypeDao {
        val rows = linkedMapOf<String, LocalCounterpartyTypeEntity>()

        override suspend fun upsert(type: LocalCounterpartyTypeEntity) {
            rows[type.id] = type
        }

        override suspend fun upsertAll(types: List<LocalCounterpartyTypeEntity>) {
            types.forEach { upsert(it) }
        }

        override suspend fun getById(id: String): LocalCounterpartyTypeEntity? =
            rows[id]?.takeIf { it.deletedAt == null }

        override suspend fun getByIdIncludingDeleted(id: String): LocalCounterpartyTypeEntity? =
            rows[id]

        override suspend fun deleteById(id: String) {
            rows.remove(id)
        }

        override suspend fun getActive(businessId: String): List<LocalCounterpartyTypeEntity> =
            rows.values.filter { it.businessId == businessId && it.deletedAt == null }

        override suspend fun getActiveByNormalizedName(
            businessId: String,
            normalizedName: String,
        ): List<LocalCounterpartyTypeEntity> =
            rows.values.filter {
                it.businessId == businessId &&
                    it.normalizedName == normalizedName &&
                    it.deletedAt == null
            }

        override suspend fun getRowsNeedingSync(
            businessId: String,
        ): List<LocalCounterpartyTypeEntity> =
            rows.values
                .filter {
                    it.businessId == businessId &&
                        it.syncStatus in setOf(
                            SyncStatus.PENDING_UPLOAD,
                            SyncStatus.PENDING_UPDATE,
                            SyncStatus.PENDING_DELETE,
                            SyncStatus.FAILED,
                        )
                }
                .sortedBy { it.createdAt }

        override fun observeByBusiness(
            businessId: String,
        ): Flow<List<LocalCounterpartyTypeEntity>> =
            flowOf(
                rows.values.filter {
                    it.businessId == businessId && it.deletedAt == null && !it.isArchived
                }
            )

        override suspend fun clear() {
            rows.clear()
        }
    }

    private class FakeMetadataDao : LocalAppMetadataDao {
        val store = mutableMapOf<String, LocalAppMetadataEntity>()

        override suspend fun upsert(entry: LocalAppMetadataEntity) {
            store[entry.key] = entry
        }

        override suspend fun get(key: String): LocalAppMetadataEntity? = store[key]

        override fun observe(key: String): Flow<LocalAppMetadataEntity?> = flowOf(store[key])
    }

    private class FakeSyncEventDao : LocalSyncEventDao {
        val rows = mutableListOf<LocalSyncEventEntity>()

        override suspend fun insert(event: LocalSyncEventEntity) {
            rows += event
        }

        override fun observeRecent(
            businessId: String,
            limit: Int,
        ): Flow<List<LocalSyncEventEntity>> =
            flowOf(rows.filter { it.businessId == businessId }.take(limit))

        override suspend fun unreported(limit: Int): List<LocalSyncEventEntity> =
            rows.filter { it.reportedAt == null }.take(limit)

        override suspend fun markReported(ids: List<String>, now: Long) {
            rows.replaceAll { if (it.id in ids) it.copy(reportedAt = now) else it }
        }

        override fun observeFailures(): Flow<List<LocalSyncEventEntity>> =
            flowOf(rows.filter { it.status == "failure" })

        override suspend fun clear() {
            rows.clear()
        }
    }
}
