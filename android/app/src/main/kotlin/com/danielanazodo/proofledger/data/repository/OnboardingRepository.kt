package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity
import javax.inject.Inject
import javax.inject.Singleton

/** First-run hints whose "seen" state is persisted in the app-metadata store. */
enum class OnboardingTip(val key: String) {
    ManageCategories("onboarding_tip_manage_categories"),
}

@Singleton
class OnboardingRepository @Inject constructor(
    private val metadataDao: LocalAppMetadataDao,
) {
    suspend fun isDismissed(tip: OnboardingTip): Boolean =
        metadataDao.get(tip.key)?.value == DISMISSED

    suspend fun dismiss(tip: OnboardingTip) {
        metadataDao.upsert(
            LocalAppMetadataEntity(
                key = tip.key,
                value = DISMISSED,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private companion object {
        const val DISMISSED = "1"
    }
}
