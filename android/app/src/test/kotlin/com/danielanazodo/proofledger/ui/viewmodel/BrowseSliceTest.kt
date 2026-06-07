package com.danielanazodo.proofledger.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseSliceTest {
    private data class Item(val label: String, val createdAt: Long)

    private fun slice(items: List<Item>, query: String, cap: Int = 5) =
        browseSlice(
            all = items,
            query = query,
            recencyKey = { it.createdAt },
            matches = { item, q -> item.label.contains(q, ignoreCase = true) },
            recentCap = cap,
        )

    private val many = (1..8).map { Item("Plot $it", createdAt = it.toLong()) }

    @Test
    fun `blank query shows newest-first recent cap and flags truncation`() {
        val s = slice(many, query = "")
        assertEquals(listOf("Plot 8", "Plot 7", "Plot 6", "Plot 5", "Plot 4"), s.visible.map { it.label })
        assertEquals(8, s.total)
        assertTrue(s.truncated)
        assertTrue(s.showSearch)
        assertFalse(s.noMatches)
    }

    @Test
    fun `short list under the cap shows everything with no search and no truncation`() {
        val s = slice(many.take(3), query = "")
        assertEquals(3, s.visible.size)
        assertFalse(s.truncated)
        assertFalse(s.showSearch)
    }

    @Test
    fun `query filters the full set case-insensitively and is not capped`() {
        val items = many + Item("Bungalow A", createdAt = 100)
        val s = slice(items, query = "plot")
        assertEquals(8, s.visible.size) // all 8 plots, not capped to 5
        assertTrue(s.visible.all { it.label.startsWith("Plot") })
        assertTrue(s.showSearch)
        assertFalse(s.truncated) // truncation hint is only for the blank-query slice
    }

    @Test
    fun `query with no matches reports noMatches and keeps the search field`() {
        val s = slice(many, query = "zzz")
        assertTrue(s.visible.isEmpty())
        assertTrue(s.noMatches)
        assertTrue(s.showSearch)
    }

    @Test
    fun `whitespace-only query is treated as blank`() {
        val s = slice(many, query = "   ")
        assertEquals("", s.query)
        assertEquals(5, s.visible.size)
    }
}
