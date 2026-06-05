package com.danielanazodo.proofledger.data.analytics

import javax.inject.Inject

/** Used when no PostHog key is configured. Drops every event on the floor. */
class NoOpAnalytics @Inject constructor() : Analytics {
    override fun track(event: String, properties: Map<String, Any>) {
        // Intentionally does nothing.
    }
}
