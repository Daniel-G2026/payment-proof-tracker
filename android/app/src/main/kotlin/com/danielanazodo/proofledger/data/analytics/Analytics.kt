package com.danielanazodo.proofledger.data.analytics

/**
 * Product-analytics seam. ViewModels and repositories depend on this interface
 * only — never on a concrete SDK — so the architecture boundary holds and tests
 * can substitute a fake. The real implementation is PostHog; when no key is
 * configured a [NoOpAnalytics] is injected instead, so debug / CI builds emit
 * nothing and need no keys.
 */
interface Analytics {
    fun track(event: String, properties: Map<String, Any> = emptyMap())
}

/** The §15 starter event names. Kept as constants so call sites can't typo. */
object AnalyticsEvents {
    const val APP_OPENED = "app_opened"
    const val BUSINESS_CREATED = "business_created"
    const val TRANSACTION_SAVED = "transaction_saved"
    const val SCREENSHOT_UPLOADED = "screenshot_uploaded"
    const val DRAFT_CONFIRMED = "draft_confirmed"
    const val EXPORT_CREATED = "export_created"
}
