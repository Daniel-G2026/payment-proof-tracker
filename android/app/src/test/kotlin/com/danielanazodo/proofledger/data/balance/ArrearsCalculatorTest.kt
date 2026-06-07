package com.danielanazodo.proofledger.data.balance

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 13 Cut 4 — the dashboard aggregation asserts the Cut 0 §6 worked
 * example to the minor unit: a buyer with two due units counted once, the
 * money total still summing both, and the per-buyer behind/due split. See
 * `docs/phase-13-allocation-contract.md` §6. ₦500,000 == 50_000_000.
 */
class ArrearsCalculatorTest {
    private val naira = 100L
    private val asOf = LocalDate.of(2026, 2, 15) // February 2026

    private fun inst(id: String, seq: Int, amount: Long, due: String) =
        Installment(id, seq, amount * naira, LocalDate.parse(due))

    private fun pay(id: String, amount: Long, date: String, earmark: String? = null) =
        Payment(id, amount * naira, LocalDate.parse(date), earmark)

    @Test
    fun `section 6 dashboard example — buyers counted once`() {
        val units = listOf(
            // Ada — Plot 12 (S1, 300k paid 01-20): behind 200k, due 500k.
            UnitScheduleData(
                unitId = "p12", unitLabel = "Plot 12", buyerId = "ada", buyerName = "Ada",
                installments = listOf(
                    inst("i1", 1, 500_000, "2026-01-31"),
                    inst("i2", 2, 500_000, "2026-02-28"),
                    inst("i3", 3, 500_000, "2026-03-31"),
                    inst("i4", 4, 500_000, "2026-04-30"),
                ),
                payments = listOf(pay("a1", 300_000, "2026-01-20")),
            ),
            // Ada — Plot 13 (single 400k due 02-20, unpaid): due 400k.
            UnitScheduleData(
                unitId = "p13", unitLabel = "Plot 13", buyerId = "ada", buyerName = "Ada",
                installments = listOf(inst("j1", 1, 400_000, "2026-02-20")),
                payments = emptyList(),
            ),
            // Bola — Plot 20 (400k due 02-10, paid 02-05): fulfilled.
            UnitScheduleData(
                unitId = "p20", unitLabel = "Plot 20", buyerId = "bola", buyerName = "Bola",
                installments = listOf(inst("k1", 1, 400_000, "2026-02-10")),
                payments = listOf(pay("b1", 400_000, "2026-02-05")),
            ),
            // Chidi — Plot 30 (only March+): nothing due in Feb, none past.
            UnitScheduleData(
                unitId = "p30", unitLabel = "Plot 30", buyerId = "chidi", buyerName = "Chidi",
                installments = listOf(inst("m1", 1, 500_000, "2026-03-31")),
                payments = emptyList(),
            ),
        )

        val dash = ArrearsCalculator.summarize(units, asOf)

        assertEquals(YearMonth.of(2026, 2), dash.month)
        // Ada counted once despite two due units; Bola counted; Chidi excluded.
        assertEquals(2, dash.peopleDueThisMonth)
        assertEquals(1, dash.peopleFulfilledThisMonth)
        // Money total sums BOTH of Ada's units (700k + 400k) + Bola 0 + Chidi 0.
        assertEquals(1_100_000 * naira, dash.combinedOutstandingMinor)

        val ada = dash.buyers.first { it.buyerId == "ada" }
        assertEquals(200_000 * naira, ada.behindMinor)
        assertEquals(900_000 * naira, ada.dueThisMonthMinor)
        assertEquals(2, ada.units.size) // unit detail underneath

        // Due items carry the due date + past-due flag for the countdown UI.
        val plot12 = ada.units.first { it.unitLabel == "Plot 12" }
        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 31) to true, // behind
                LocalDate.of(2026, 2, 28) to false, // due this month
            ),
            plot12.dueItems.map { it.dueDate to it.pastDue },
        )
        val plot13 = ada.units.first { it.unitLabel == "Plot 13" }
        assertEquals(listOf(LocalDate.of(2026, 2, 20)), plot13.dueItems.map { it.dueDate })
        // Bola is fully paid → nothing owed → no due items.
        assertEquals(emptyList<Long>(), dash.buyers.first { it.buyerId == "bola" }.units.flatMap { it.dueItems }.map { it.shortfallMinor })

        val bola = dash.buyers.first { it.buyerId == "bola" }
        assertEquals(0L, bola.combinedMinor)
        assertEquals(true, bola.fulfilledThisMonth)

        // Chidi has nothing due this month → not in the due/fulfilled counts.
        val chidi = dash.buyers.firstOrNull { it.buyerId == "chidi" }
        assertEquals(false, chidi?.dueThisMonth ?: false)
    }

    @Test
    fun `combined total equals arrears at month end (identity)`() {
        val installments = listOf(
            inst("i1", 1, 500_000, "2026-01-31"),
            inst("i2", 2, 500_000, "2026-02-28"),
            inst("i3", 3, 500_000, "2026-03-31"),
        )
        val payments = listOf(pay("p1", 300_000, "2026-01-20"))
        val unit = UnitScheduleData("u", "Plot", "buyer", "Buyer", installments, payments)

        val dash = ArrearsCalculator.summarize(listOf(unit), asOf)

        val monthEnd = LocalDate.of(2026, 2, 28)
        val arrearsAtMonthEnd = AllocationEngine.allocate(installments, payments, monthEnd).arrearsMinor
        assertEquals(arrearsAtMonthEnd, dash.combinedOutstandingMinor)
        assertEquals(arrearsAtMonthEnd, dash.buyers.single().combinedMinor)
    }

    @Test
    fun `unit with a schedule but no buyer is excluded from the home dashboard`() {
        val units = listOf(
            // Assigned buyer Ada: 500k due this month, unpaid → counts.
            UnitScheduleData(
                unitId = "p1", unitLabel = "Plot 1", buyerId = "ada", buyerName = "Ada",
                installments = listOf(inst("i1", 1, 500_000, "2026-02-10")),
                payments = emptyList(),
            ),
            // No buyer yet (offer letter scanned before assigning): 900k past-due
            // + this-month, but must NOT surface anywhere on the home dashboard.
            UnitScheduleData(
                unitId = "p2", unitLabel = "Plot 2", buyerId = null, buyerName = null,
                installments = listOf(
                    inst("j1", 1, 400_000, "2026-01-31"),
                    inst("j2", 2, 500_000, "2026-02-20"),
                ),
                payments = emptyList(),
            ),
        )

        val dash = ArrearsCalculator.summarize(units, asOf)

        // Only Ada's 500k counts; the buyer-less unit's 900k is excluded.
        assertEquals(500_000 * naira, dash.combinedOutstandingMinor)
        assertEquals(1, dash.peopleDueThisMonth)
        // The buyer-less unit produces no buyer row.
        assertEquals(listOf("ada"), dash.buyers.map { it.buyerId })
    }

    @Test
    fun `empty input yields a clean zero dashboard`() {
        val dash = ArrearsCalculator.summarize(emptyList(), asOf)
        assertEquals(0, dash.peopleDueThisMonth)
        assertEquals(0, dash.peopleFulfilledThisMonth)
        assertEquals(0L, dash.combinedOutstandingMinor)
        assertEquals(emptyList<BuyerArrears>(), dash.buyers)
    }
}
