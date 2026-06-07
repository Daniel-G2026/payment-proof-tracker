package com.danielanazodo.proofledger.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class MainTabTest {

    @Test
    fun `bottom nav has the five expected tabs in order`() {
        // Nav contract: Projects is a top-level tab (Phase 13); Reports moved to a
        // Home card and Settings no longer hosts Projects.
        assertEquals(
            listOf("Home", "Records", "Projects", "People", "Settings"),
            MainTab.entries.map { it.label },
        )
    }
}
