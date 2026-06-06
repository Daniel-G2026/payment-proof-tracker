package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingRepositoryTest {

    private class FakeMetadataDao : LocalAppMetadataDao {
        val store = mutableMapOf<String, LocalAppMetadataEntity>()
        override suspend fun upsert(entry: LocalAppMetadataEntity) {
            store[entry.key] = entry
        }
        override suspend fun get(key: String): LocalAppMetadataEntity? = store[key]
        override fun observe(key: String): Flow<LocalAppMetadataEntity?> = flowOf(store[key])
    }

    @Test
    fun `tip is not dismissed by default`() = runBlocking {
        val repo = OnboardingRepository(FakeMetadataDao())
        assertFalse(repo.isDismissed(OnboardingTip.ManageCategories))
    }

    @Test
    fun `dismiss persists and is read back`() = runBlocking {
        val repo = OnboardingRepository(FakeMetadataDao())
        repo.dismiss(OnboardingTip.ManageCategories)
        assertTrue(repo.isDismissed(OnboardingTip.ManageCategories))
    }

    @Test
    fun `repeated dismiss is idempotent`() = runBlocking {
        val dao = FakeMetadataDao()
        val repo = OnboardingRepository(dao)
        repeat(50) { repo.dismiss(OnboardingTip.ManageCategories) }
        assertTrue(repo.isDismissed(OnboardingTip.ManageCategories))
        // One key, one row — no duplication under repetition.
        assertTrue(dao.store.size == 1)
    }
}
