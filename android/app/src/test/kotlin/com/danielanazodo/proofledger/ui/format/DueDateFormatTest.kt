package com.danielanazodo.proofledger.ui.format

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DueDateFormatTest {
    private val today = LocalDate.of(2026, 6, 6)

    @Test
    fun `future countdowns read in days under a week, weeks at or above`() {
        assertEquals("due today", relativeDueLabel(today, today))
        assertEquals("in 1 day", relativeDueLabel(today.plusDays(1), today))
        assertEquals("in 3 days", relativeDueLabel(today.plusDays(3), today))
        assertEquals("in 1 week", relativeDueLabel(today.plusDays(7), today))
        assertEquals("in 3 weeks", relativeDueLabel(today.plusDays(21), today))
    }

    @Test
    fun `past due reads as overdue`() {
        assertEquals("3 days overdue", relativeDueLabel(today.minusDays(3), today))
        assertEquals("1 week overdue", relativeDueLabel(today.minusDays(7), today))
        assertEquals("2 weeks overdue", relativeDueLabel(today.minusDays(14), today))
    }

    @Test
    fun `due date is short day-month`() {
        assertEquals("20 Jun", formatDueDate(LocalDate.of(2026, 6, 20)))
    }
}
