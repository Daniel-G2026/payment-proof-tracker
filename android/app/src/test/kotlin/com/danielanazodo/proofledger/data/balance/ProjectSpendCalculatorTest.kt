package com.danielanazodo.proofledger.data.balance

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSpendCalculatorTest {

    @Test
    fun `groups money-out by category and totals, biggest first`() {
        val spend = ProjectSpendCalculator.summarize(
            listOf(
                "Materials" to 5_000_000L,
                "Wages" to 3_000_000L,
                "Materials" to 2_000_000L,
                "Subcontractor" to 8_000_000L,
            ),
        )
        assertEquals(18_000_000L, spend.totalMinor)
        assertEquals(
            listOf(
                ProjectSpendLine("Subcontractor", 8_000_000L),
                ProjectSpendLine("Materials", 7_000_000L),
                ProjectSpendLine("Wages", 3_000_000L),
            ),
            spend.byCategory,
        )
    }

    @Test
    fun `blank or null categories fall under Uncategorised`() {
        val spend = ProjectSpendCalculator.summarize(
            listOf(null to 1_000L, "" to 2_000L, "  " to 500L),
        )
        assertEquals(3_500L, spend.totalMinor)
        assertEquals(
            listOf(ProjectSpendLine(ProjectSpendCalculator.UNCATEGORIZED, 3_500L)),
            spend.byCategory,
        )
    }

    @Test
    fun `empty input is a clean zero`() {
        val spend = ProjectSpendCalculator.summarize(emptyList())
        assertEquals(0L, spend.totalMinor)
        assertEquals(emptyList<ProjectSpendLine>(), spend.byCategory)
    }
}
