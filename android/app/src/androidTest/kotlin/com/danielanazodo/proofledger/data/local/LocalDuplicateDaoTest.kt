package com.danielanazodo.proofledger.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDuplicateDaoTest {
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
    fun duplicateCandidateQuery_ignoresDeletedAndExcludedRows() = runBlocking {
        val dao = db.localTransactionDao()
        dao.upsert(row(id = "match"))
        dao.upsert(row(id = "deleted", deletedAt = 100L))
        dao.upsert(row(id = "self"))

        val rows = dao.findDuplicateCandidates(
            businessId = "b1",
            amountMinor = 5000,
            direction = "money_in",
            transactionDate = "2026-05-24",
            referenceNumber = null,
            excludeTransactionId = "self",
        )

        assertEquals(listOf("match"), rows.map { it.id })
    }

    @Test
    fun duplicateCandidateQuery_returnsReferenceAndAmountDateRowsOnlyForSameBusiness() = runBlocking {
        val dao = db.localTransactionDao()
        dao.upsert(row(id = "same-reference", amountMinor = 1000, referenceNumber = "REF 1"))
        dao.upsert(row(id = "same-amount-date", amountMinor = 5000, referenceNumber = null))
        dao.upsert(row(id = "other-business", businessId = "b2", referenceNumber = "REF 1"))
        dao.upsert(row(id = "unrelated", amountMinor = 9999, referenceNumber = null))

        val rows = dao.findDuplicateCandidates(
            businessId = "b1",
            amountMinor = 5000,
            direction = "money_in",
            transactionDate = "2026-05-24",
            referenceNumber = "ref1",
            excludeTransactionId = null,
        )

        assertEquals(setOf("same-reference", "same-amount-date"), rows.map { it.id }.toSet())
        assertTrue(rows.none { it.businessId == "b2" })
    }

    private fun row(
        id: String,
        businessId: String = "b1",
        amountMinor: Long = 5000,
        direction: String = "money_in",
        transactionDate: String = "2026-05-24",
        counterpartyId: String? = "cp1",
        counterpartyName: String? = "Ada",
        referenceNumber: String? = null,
        deletedAt: Long? = null,
    ) = LocalTransactionEntity(
        id = id,
        businessId = businessId,
        amountMinor = amountMinor,
        currency = "NGN",
        direction = direction,
        transactionDate = transactionDate,
        counterpartyId = counterpartyId,
        counterpartyName = counterpartyName,
        category = null,
        note = null,
        bankName = null,
        referenceNumber = referenceNumber,
        source = "manual",
        status = "confirmed",
        createdAt = 1L,
        updatedAt = 1L,
        deletedAt = deletedAt,
        syncStatus = SyncStatus.SYNCED,
        lastSyncAttemptAt = null,
    )
}
