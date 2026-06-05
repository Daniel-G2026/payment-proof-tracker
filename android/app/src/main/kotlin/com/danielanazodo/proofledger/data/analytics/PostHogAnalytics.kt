package com.danielanazodo.proofledger.data.analytics

import com.posthog.PostHog

/**
 * Real analytics implementation backed by PostHog. The SDK is initialized once
 * via PostHogAndroid.setup() in the Hilt module before this is constructed, so
 * here we only forward events to the global PostHog client.
 */
class PostHogAnalytics : Analytics {
    override fun track(event: String, properties: Map<String, Any>) {
        PostHog.capture(
            event = event,
            properties = properties.ifEmpty { null },
        )
    }
}
