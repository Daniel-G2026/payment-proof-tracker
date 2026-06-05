package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalProjectDao
import com.danielanazodo.proofledger.data.local.dao.LocalSyncEventDao
import com.danielanazodo.proofledger.data.local.entity.LocalProjectEntity
import com.danielanazodo.proofledger.data.local.entity.LocalSyncEventEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.ProjectCreateDto
import com.danielanazodo.proofledger.data.remote.dto.ProjectDto
import com.danielanazodo.proofledger.data.remote.dto.ProjectUpdateDto
import com.danielanazodo.proofledger.data.sync.SyncEventLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import retrofit2.HttpException
import retrofit2.Response

class ProjectRepositoryTest {
    private val businessId = "business-1"

    @Test
    fun `createProject syncs immediately and marks the row SYNCED`() = runBlocking {
        val dao = FakeProjectDao()
        val api = FakeApi()
        val events = FakeSyncEvents()
        val repo = ProjectRepository(api.service, dao, SyncEventLogger(events))

        val created = repo.createProject(businessId, "  Sunrise Estate ", note = " east ")

        assertEquals("Sunrise Estate", created.name)
        assertEquals("east", created.note)
        assertEquals(SyncStatus.SYNCED, dao.getByIdIncludingDeleted(created.id)!!.syncStatus)
        assertEquals(1, api.createCalls)
        val event = events.rows.single()
        assertEquals("project", event.entityType)
        assertEquals("create", event.action)
        assertEquals("success", event.status)
    }

    @Test
    fun `createProject offline keeps the row FAILED and logs failure`() = runBlocking {
        val dao = FakeProjectDao()
        val api = FakeApi(createFailure = RuntimeException("offline"))
        val events = FakeSyncEvents()
        val repo = ProjectRepository(api.service, dao, SyncEventLogger(events))

        val created = repo.createProject(businessId, "Sunrise")

        assertEquals(SyncStatus.FAILED, dao.getByIdIncludingDeleted(created.id)!!.syncStatus)
        assertEquals("failure", events.rows.single().status)
    }

    @Test
    fun `syncPending drains a pending upload created offline`() = runBlocking {
        val dao = FakeProjectDao()
        val api = FakeApi()
        dao.upsert(project(syncStatus = SyncStatus.PENDING_UPLOAD))
        val repo = ProjectRepository(api.service, dao, SyncEventLogger(FakeSyncEvents()))

        repo.syncPending(businessId)

        assertEquals(SyncStatus.SYNCED, dao.getByIdIncludingDeleted("project-1")!!.syncStatus)
        assertEquals(1, api.createCalls)
    }

    @Test
    fun `refreshFromRemote stores server projects as SYNCED`() = runBlocking {
        val dao = FakeProjectDao()
        val api = FakeApi(
            remote = listOf(
                projectDto(id = "remote-1", name = "Sunrise"),
                projectDto(id = "remote-2", name = "Hilltop"),
            ),
        )
        val repo = ProjectRepository(api.service, dao, SyncEventLogger(FakeSyncEvents()))

        repo.refreshFromRemote(businessId)

        val rows = repo.observeByBusiness(businessId).first()
        assertEquals(setOf("Sunrise", "Hilltop"), rows.map { it.name }.toSet())
        assertTrue(rows.all { it.syncStatus == SyncStatus.SYNCED })
    }

    @Test
    fun `deleteProject of a never-synced row removes it locally without a backend call`() = runBlocking {
        val dao = FakeProjectDao()
        val api = FakeApi()
        dao.upsert(project(syncStatus = SyncStatus.PENDING_UPLOAD))
        val repo = ProjectRepository(api.service, dao, SyncEventLogger(FakeSyncEvents()))

        repo.deleteProject("project-1")

        assertNull(dao.getByIdIncludingDeleted("project-1"))
        assertEquals(0, api.deleteCalls)
    }

    @Test
    fun `deleteProject of a synced row soft-deletes via backend`() = runBlocking {
        val dao = FakeProjectDao()
        val api = FakeApi()
        val events = FakeSyncEvents()
        dao.upsert(project(syncStatus = SyncStatus.SYNCED))
        val repo = ProjectRepository(api.service, dao, SyncEventLogger(events))

        repo.deleteProject("project-1")

        assertEquals(SyncStatus.SYNCED, dao.getByIdIncludingDeleted("project-1")!!.syncStatus)
        assertEquals(1, api.deleteCalls)
        assertEquals("delete", events.rows.single().action)
    }

    private fun project(
        id: String = "project-1",
        syncStatus: SyncStatus,
    ): LocalProjectEntity = LocalProjectEntity(
        id = id,
        businessId = businessId,
        name = "Sunrise",
        note = null,
        createdAt = 100L,
        updatedAt = 100L,
        deletedAt = null,
        syncStatus = syncStatus,
        lastSyncAttemptAt = null,
    )

    private fun projectDto(id: String, name: String): ProjectDto = ProjectDto(
        id = id,
        businessId = businessId,
        name = name,
        note = null,
        createdAt = "2026-06-01T00:00:00Z",
        updatedAt = "2026-06-01T00:00:00Z",
        deletedAt = null,
    )

    /** ApiService via interface delegation to a Mockito mock, overriding only
     * the project endpoints — avoids hand-stubbing every method. */
    private class FakeApi(
        private val remote: List<ProjectDto> = emptyList(),
        private val createFailure: Throwable? = null,
        private val delete404: Boolean = false,
    ) {
        var createCalls = 0
        var deleteCalls = 0
        private val base = Mockito.mock(ApiService::class.java)
        val service: ApiService = object : ApiService by base {
            override suspend fun listProjects(businessId: String): List<ProjectDto> = remote

            override suspend fun createProject(
                businessId: String,
                body: ProjectCreateDto,
            ): ProjectDto {
                createCalls += 1
                createFailure?.let { throw it }
                return ProjectDto(
                    id = body.id,
                    businessId = businessId,
                    name = body.name,
                    note = body.note,
                    createdAt = "2026-06-01T00:00:00Z",
                    updatedAt = "2026-06-01T00:00:00Z",
                    deletedAt = null,
                )
            }

            override suspend fun updateProject(
                businessId: String,
                projectId: String,
                body: ProjectUpdateDto,
            ): ProjectDto = ProjectDto(
                id = projectId,
                businessId = businessId,
                name = body.name ?: "Sunrise",
                note = body.note,
                createdAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z",
                deletedAt = null,
            )

            override suspend fun deleteProject(businessId: String, projectId: String) {
                deleteCalls += 1
                if (delete404) throw HttpException(Response.error<Any>(404, "".toResponseBody(null)))
            }
        }
    }

    private class FakeSyncEvents : LocalSyncEventDao {
        val rows = mutableListOf<LocalSyncEventEntity>()
        override suspend fun insert(event: LocalSyncEventEntity) { rows += event }
        override fun observeRecent(businessId: String, limit: Int): Flow<List<LocalSyncEventEntity>> =
            flowOf(rows.filter { it.businessId == businessId }.take(limit))
        override suspend fun unreported(limit: Int): List<LocalSyncEventEntity> =
            rows.filter { it.reportedAt == null }.take(limit)
        override suspend fun markReported(ids: List<String>, now: Long) {
            rows.replaceAll { if (it.id in ids) it.copy(reportedAt = now) else it }
        }
        override fun observeFailures(): Flow<List<LocalSyncEventEntity>> =
            flowOf(rows.filter { it.status == "failure" })
        override suspend fun clear() { rows.clear() }
    }

    private class FakeProjectDao : LocalProjectDao {
        private val rows = linkedMapOf<String, LocalProjectEntity>()
        override suspend fun upsert(project: LocalProjectEntity) { rows[project.id] = project }
        override suspend fun upsertAll(projects: List<LocalProjectEntity>) = projects.forEach { upsert(it) }
        override suspend fun getById(id: String): LocalProjectEntity? =
            rows[id]?.takeIf { it.deletedAt == null }
        override suspend fun getByIdIncludingDeleted(id: String): LocalProjectEntity? = rows[id]
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override suspend fun getRowsNeedingSync(businessId: String): List<LocalProjectEntity> =
            rows.values.filter {
                it.businessId == businessId &&
                    it.syncStatus in setOf(
                        SyncStatus.PENDING_UPLOAD,
                        SyncStatus.PENDING_UPDATE,
                        SyncStatus.PENDING_DELETE,
                        SyncStatus.FAILED,
                    )
            }.sortedBy { it.createdAt }
        override fun observeByBusiness(businessId: String): Flow<List<LocalProjectEntity>> =
            flowOf(rows.values.filter { it.businessId == businessId && it.deletedAt == null })
        override suspend fun clear() { rows.clear() }
    }
}
