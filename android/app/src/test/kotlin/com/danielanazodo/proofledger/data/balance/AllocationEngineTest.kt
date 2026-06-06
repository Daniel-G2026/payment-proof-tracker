package com.danielanazodo.proofledger.data.balance

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 13 Cut 2 — the Android engine asserts the Cut 0 worked examples, to the
 * minor unit, identically to the backend `test_phase13_allocation.py`. See
 * `docs/phase-13-allocation-contract.md` §5–§6. ₦500,000 == 50_000_000.
 */
class AllocationEngineTest {
    private val naira = 100L

    private fun s1(): List<Installment> = listOf(
        Installment("i1", 1, 500_000 * naira, LocalDate.of(2026, 1, 31)),
        Installment("i2", 2, 500_000 * naira, LocalDate.of(2026, 2, 28)),
        Installment("i3", 3, 500_000 * naira, LocalDate.of(2026, 3, 31)),
        Installment("i4", 4, 500_000 * naira, LocalDate.of(2026, 4, 30)),
    )

    private fun AllocationResult.bySeq(): Map<Int, InstallmentFill> = fills.associateBy { it.sequence }

    private fun assertFill(
        fill: InstallmentFill,
        state: InstallmentState,
        filled: Long,
        shortfall: Long,
    ) {
        assertEquals("seq ${fill.sequence} state", state, fill.state)
        assertEquals("seq ${fill.sequence} filled", filled * naira, fill.filledMinor)
        assertEquals("seq ${fill.sequence} shortfall", shortfall * naira, fill.shortfallMinor)
    }

    @Test
    fun `example A — 300k partial`() {
        val result = AllocationEngine.allocate(
            s1(),
            listOf(Payment("p1", 300_000 * naira, LocalDate.of(2026, 1, 20))),
            asOf = LocalDate.of(2026, 2, 1),
        )
        val f = result.bySeq()
        assertFill(f.getValue(1), InstallmentState.PARTIAL, 300_000, 200_000)
        assertFill(f.getValue(2), InstallmentState.UNPAID, 0, 500_000)
        assertFill(f.getValue(3), InstallmentState.UNPAID, 0, 500_000)
        assertFill(f.getValue(4), InstallmentState.UNPAID, 0, 500_000)
        assertEquals(0L, result.creditMinor)
        assertEquals(200_000 * naira, result.arrearsMinor)
    }

    @Test
    fun `example B1 — 800k cascade before I2 due`() {
        val result = AllocationEngine.allocate(
            s1(),
            listOf(Payment("p1", 800_000 * naira, LocalDate.of(2026, 1, 25))),
            asOf = LocalDate.of(2026, 2, 15),
        )
        val f = result.bySeq()
        assertFill(f.getValue(1), InstallmentState.FILLED, 500_000, 0)
        assertFill(f.getValue(2), InstallmentState.PARTIAL, 300_000, 200_000)
        assertEquals(0L, result.arrearsMinor)
    }

    @Test
    fun `example B2 — 800k cascade after I2 due`() {
        val result = AllocationEngine.allocate(
            s1(),
            listOf(Payment("p1", 800_000 * naira, LocalDate.of(2026, 1, 25))),
            asOf = LocalDate.of(2026, 3, 1),
        )
        assertFill(result.bySeq().getValue(2), InstallmentState.PARTIAL, 300_000, 200_000)
        assertEquals(200_000 * naira, result.arrearsMinor)
    }

    @Test
    fun `example C1 — 1m bulk late before I3 due`() {
        val result = AllocationEngine.allocate(
            s1(),
            listOf(
                Payment("p1", 300_000 * naira, LocalDate.of(2026, 1, 20)),
                Payment("p2", 1_000_000 * naira, LocalDate.of(2026, 3, 15)),
            ),
            asOf = LocalDate.of(2026, 3, 20),
        )
        val f = result.bySeq()
        assertFill(f.getValue(1), InstallmentState.FILLED_LATE, 500_000, 0)
        assertFill(f.getValue(2), InstallmentState.FILLED_LATE, 500_000, 0)
        assertFill(f.getValue(3), InstallmentState.PARTIAL, 300_000, 200_000)
        assertFill(f.getValue(4), InstallmentState.UNPAID, 0, 500_000)
        assertEquals(0L, result.creditMinor)
        assertEquals(0L, result.arrearsMinor)
    }

    @Test
    fun `example C2 — 1m bulk late after I3 due`() {
        val result = AllocationEngine.allocate(
            s1(),
            listOf(
                Payment("p1", 300_000 * naira, LocalDate.of(2026, 1, 20)),
                Payment("p2", 1_000_000 * naira, LocalDate.of(2026, 3, 15)),
            ),
            asOf = LocalDate.of(2026, 4, 1),
        )
        assertEquals(200_000 * naira, result.arrearsMinor)
    }

    @Test
    fun `example D — back-dated payment un-lates I1`() {
        val payments = listOf(
            Payment("p0", 600_000 * naira, LocalDate.of(2026, 1, 15)),
            Payment("p2", 1_000_000 * naira, LocalDate.of(2026, 3, 15)),
        )
        val result = AllocationEngine.allocate(s1(), payments, asOf = LocalDate.of(2026, 3, 20))
        val f = result.bySeq()
        assertFill(f.getValue(1), InstallmentState.FILLED, 500_000, 0)
        assertFill(f.getValue(2), InstallmentState.FILLED_LATE, 500_000, 0)
        assertFill(f.getValue(3), InstallmentState.FILLED_AHEAD, 500_000, 0)
        assertFill(f.getValue(4), InstallmentState.PARTIAL, 100_000, 400_000)
        assertEquals(0L, result.creditMinor)
        assertEquals(0L, result.arrearsMinor)
    }

    @Test
    fun `example E — earmark beats oldest-first`() {
        val result = AllocationEngine.allocate(
            s1(),
            listOf(
                Payment("p1", 500_000 * naira, LocalDate.of(2026, 2, 10), earmarkedInstallmentId = "i3"),
            ),
            asOf = LocalDate.of(2026, 2, 15),
        )
        val f = result.bySeq()
        assertFill(f.getValue(1), InstallmentState.UNPAID, 0, 500_000)
        assertFill(f.getValue(3), InstallmentState.FILLED_AHEAD, 500_000, 0)
        assertEquals(500_000 * naira, result.arrearsMinor)
    }

    @Test
    fun `example E contrast — no earmark clears arrears`() {
        val result = AllocationEngine.allocate(
            s1(),
            listOf(Payment("p1", 500_000 * naira, LocalDate.of(2026, 2, 10))),
            asOf = LocalDate.of(2026, 2, 15),
        )
        // 02-10 lands on I1 (due 01-31): late, but arrears 0 because it's filled.
        assertFill(result.bySeq().getValue(1), InstallmentState.FILLED_LATE, 500_000, 0)
        assertEquals(0L, result.arrearsMinor)
    }

    @Test
    fun `example F — earmark drops on renegotiation`() {
        val v2 = listOf(
            Installment("j1", 1, 400_000 * naira, LocalDate.of(2026, 2, 28)),
            Installment("j2", 2, 800_000 * naira, LocalDate.of(2026, 4, 30)),
            Installment("j3", 3, 800_000 * naira, LocalDate.of(2026, 6, 30)),
        )
        val result = AllocationEngine.allocate(
            v2,
            listOf(
                // Earmark targets v1's i3, absent from active v2 -> does not bind.
                Payment("p1", 500_000 * naira, LocalDate.of(2026, 2, 10), earmarkedInstallmentId = "i3"),
            ),
            asOf = LocalDate.of(2026, 3, 1),
        )
        val f = result.bySeq()
        assertFill(f.getValue(1), InstallmentState.FILLED, 400_000, 0)
        assertFill(f.getValue(2), InstallmentState.PARTIAL, 100_000, 700_000)
        assertFill(f.getValue(3), InstallmentState.UNPAID, 0, 800_000)
        assertEquals(0L, result.creditMinor)
        assertEquals(0L, result.arrearsMinor)
    }

    @Test
    fun `example G — overpayment surfaces as credit`() {
        val result = AllocationEngine.allocate(
            s1(),
            listOf(Payment("p1", 2_500_000 * naira, LocalDate.of(2026, 1, 15))),
            asOf = LocalDate.of(2026, 5, 1),
        )
        val f = result.bySeq()
        for (seq in 1..4) assertFill(f.getValue(seq), InstallmentState.FILLED, 500_000, 0)
        assertEquals(500_000 * naira, result.creditMinor)
        assertEquals(0L, result.arrearsMinor)
    }

    @Test
    fun `no active schedule — all money becomes credit`() {
        val result = AllocationEngine.allocate(
            emptyList(),
            listOf(Payment("p1", 300_000 * naira, LocalDate.of(2026, 1, 20))),
            asOf = LocalDate.of(2026, 2, 1),
        )
        assertEquals(emptyList<InstallmentFill>(), result.fills)
        assertEquals(300_000 * naira, result.creditMinor)
        assertEquals(0L, result.arrearsMinor)
    }
}
