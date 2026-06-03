package com.danielanazodo.proofledger.ui.format

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportPeriodTest {

    @Test
    fun `monthContaining spans the whole calendar month`() {
        val period = monthContaining("2026-06-15")
        assertEquals(PeriodUnit.MONTH, period.unit)
        assertEquals("2026-06-01", period.startIso)
        assertEquals("2026-06-30", period.endIso)
        assertEquals("June 2026", period.label)
    }

    @Test
    fun `monthContaining handles february length`() {
        val period = monthContaining("2026-02-10")
        assertEquals("2026-02-01", period.startIso)
        assertEquals("2026-02-28", period.endIso)
    }

    @Test
    fun `month previous and next step across year boundaries`() {
        assertEquals("2026-05-01", monthContaining("2026-06-15").previous().startIso)
        val december = monthContaining("2026-12-10")
        assertEquals("2027-01-01", december.next().startIso)
        assertEquals("2027-01-31", december.next().endIso)
    }

    @Test
    fun `weekContaining is a seven day inclusive window containing the date`() {
        val period = weekContaining("2026-06-17")
        assertEquals(PeriodUnit.WEEK, period.unit)
        assertTrue(period.contains("2026-06-17"))
        assertEquals(6, daysBetween(period.startIso, period.endIso))
    }

    @Test
    fun `week previous shifts back seven days`() {
        val period = weekContaining("2026-06-17")
        assertEquals(7, daysBetween(period.previous().startIso, period.startIso))
    }

    @Test
    fun `periodsCovering returns distinct months newest first including current`() {
        val dates = listOf("2026-04-10", "2026-04-25", "2026-02-01")
        val periods = periodsCovering(PeriodUnit.MONTH, dates, todayIso = "2026-06-01")

        // Current (June) is always present even with no records in it.
        assertEquals(listOf("2026-06-01", "2026-04-01", "2026-02-01"), periods.map { it.startIso })
    }

    @Test
    fun `relativeLabel prefers this month when today falls inside`() {
        val june = monthContaining("2026-06-15")
        assertEquals("This month", june.relativeLabel(todayIso = "2026-06-02"))
        assertEquals("June 2026", june.relativeLabel(todayIso = "2026-07-02"))
    }

    private fun daysBetween(startIso: String, endIso: String): Long {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val start = fmt.parse(startIso)!!.time
        val end = fmt.parse(endIso)!!.time
        return (end - start) / (24L * 60 * 60 * 1000)
    }
}
