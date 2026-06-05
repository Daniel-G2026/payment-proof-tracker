package com.danielanazodo.proofledger.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class MainTabTest {

    @Test
    fun `bottom nav has the five expected tabs in order`() {
        // Guards the Phase 12 nav contract: Home, Records, Reports, People, Settings.
        assertEquals(
            listOf("Home", "Records", "Reports", "People", "Settings"),
            MainTab.entries.map { it.label },
        )
    }
}
