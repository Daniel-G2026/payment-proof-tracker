package com.danielanazodo.proofledger.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyTypeEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalCounterpartyTypeDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeByBusiness_excludesDeletedAndArchivedAndSortsByNormalizedName() = runBlocking {
        val dao = db.localCounterpartyTypeDao()
        dao.upsert(type(id = "t-sub", name = "subcontractor"))
        dao.upsert(type(id = "t-cust", name = "customer"))
        dao.upsert(type(id = "t-del", name = "buyer", deletedAt = 100L))
        dao.upsert(type(id = "t-arch", name = "agent", isArchived = true))
        dao.upsert(type(id = "t-other", name = "tenant", businessId = "b2"))

        val rows = dao.observeByBusiness("b1").first()

        assertEquals(listOf("customer", "subcontractor"), rows.map { it.normalizedName })
    }

    @Test
    fun getActiveByNormalizedName_matchesOnlyLiveRows() = runBlocking {
        val dao = db.localCounterpartyTypeDao()
        dao.upsert(type(id = "live", name = "land_owner"))
        dao.upsert(type(id = "dead", name = "land_owner", deletedAt = 1L))

        val matches = dao.getActiveByNormalizedName("b1", "land_owner")

        assertEquals(listOf("live"), matches.map { it.id })
    }

    @Test
    fun getRowsNeedingSync_returnsPendingAndFailedOnly() = runBlocking {
        val dao = db.localCounterpartyTypeDao()
        dao.upsert(type(id = "synced", name = "customer", syncStatus = SyncStatus.SYNCED))
        dao.upsert(type(id = "pending", name = "supplier", syncStatus = SyncStatus.PENDING_UPLOAD))
        dao.upsert(type(id = "failed", name = "employee", syncStatus = SyncStatus.FAILED))

        val rows = dao.getRowsNeedingSync("b1")

        assertEquals(setOf("pending", "failed"), rows.map { it.id }.toSet())
        assertTrue(rows.none { it.id == "synced" })
    }

    private fun type(
        id: String,
        name: String,
        businessId: String = "b1",
        deletedAt: Long? = null,
        isArchived: Boolean = false,
        syncStatus: SyncStatus = SyncStatus.SYNCED,
    ) = LocalCounterpartyTypeEntity(
        id = id,
        businessId = businessId,
        name = name,
        normalizedName = name,
        isArchived = isArchived,
        createdAt = 1L,
        updatedAt = 1L,
        deletedAt = deletedAt,
        syncStatus = syncStatus,
        lastSyncAttemptAt = null,
    )
}
