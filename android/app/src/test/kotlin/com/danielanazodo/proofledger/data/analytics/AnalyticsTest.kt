package com.danielanazodo.proofledger.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records events so tests can assert on emissions without an SDK. */
class FakeAnalytics : Analytics {
    val events = mutableListOf<Pair<String, Map<String, Any>>>()
    override fun track(event: String, properties: Map<String, Any>) {
        events += event to properties
    }
}

class AnalyticsTest {

    @Test
    fun `no-op analytics never throws`() {
        val analytics = NoOpAnalytics()
        analytics.track(AnalyticsEvents.APP_OPENED)
        analytics.track(AnalyticsEvents.TRANSACTION_SAVED, mapOf("direction" to "money_in"))
        // Reaching here without an exception is the assertion.
        assertTrue(true)
    }

    @Test
    fun `fake records event names and properties`() {
        val analytics = FakeAnalytics()
        analytics.track(AnalyticsEvents.TRANSACTION_SAVED, mapOf("has_proof" to true))

        assertEquals(1, analytics.events.size)
        assertEquals(AnalyticsEvents.TRANSACTION_SAVED, analytics.events.single().first)
        assertEquals(true, analytics.events.single().second["has_proof"])
    }

    @Test
    fun `starter event names are stable`() {
        assertEquals("app_opened", AnalyticsEvents.APP_OPENED)
        assertEquals("business_created", AnalyticsEvents.BUSINESS_CREATED)
        assertEquals("transaction_saved", AnalyticsEvents.TRANSACTION_SAVED)
        assertEquals("screenshot_uploaded", AnalyticsEvents.SCREENSHOT_UPLOADED)
        assertEquals("draft_confirmed", AnalyticsEvents.DRAFT_CONFIRMED)
        assertEquals("export_created", AnalyticsEvents.EXPORT_CREATED)
    }
}
