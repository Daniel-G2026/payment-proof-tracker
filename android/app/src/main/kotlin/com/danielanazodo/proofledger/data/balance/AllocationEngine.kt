package com.danielanazodo.proofledger.data.balance

import java.time.LocalDate

/**
 * Pure waterfall allocation engine (Phase 13). The Android mirror of the backend
 * `app/services/allocation.py`; **both must reproduce the worked examples in
 * `docs/phase-13-allocation-contract.md` exactly, to the minor unit.** If a rule
 * changes, change the contract doc first, then both engines + tests together.
 *
 * Nothing is persisted: fills, states, arrears and credit are recomputed on
 * every call from the active schedule's installments and the unit's payments —
 * so back-dated / corrected payments and re-poured renegotiations self-correct.
 *
 * No Room / Compose dependencies — fully unit-testable. All amounts are integer
 * minor units (kobo); no floating point, no rounding, no division.
 */
enum class InstallmentState { UNPAID, PARTIAL, FILLED, FILLED_LATE, FILLED_AHEAD }

data class Installment(
    val id: String,
    val sequence: Int,
    val amountMinor: Long,
    val dueDate: LocalDate,
)

data class Payment(
    val id: String,
    val amountMinor: Long,
    val date: LocalDate,
    val earmarkedInstallmentId: String? = null,
)

data class InstallmentFill(
    val sequence: Int,
    val installmentId: String,
    val dueDate: LocalDate,
    val scheduledMinor: Long,
    val filledMinor: Long,
    val shortfallMinor: Long,
    val onTimeCoverageMinor: Long,
    val state: InstallmentState,
)

data class AllocationResult(
    val fills: List<InstallmentFill>,
    val creditMinor: Long,
    val arrearsMinor: Long,
)

object AllocationEngine {
    fun allocate(
        installments: List<Installment>,
        payments: List<Payment>,
        asOf: LocalDate,
    ): AllocationResult {
        // §1.1 deterministic sort.
        val insts = installments.sortedWith(compareBy({ it.dueDate }, { it.sequence }))
        val pays = payments.sortedWith(compareBy({ it.date }, { it.id }))
        val n = insts.size

        val filled = LongArray(n)
        // Per-installment contributions (amount, paymentDate) for the
        // order-independent on-time-coverage rule (contract §3).
        val contribs = Array(n) { mutableListOf<Pair<Long, LocalDate>>() }
        val indexById = insts.withIndex().associate { (i, inst) -> inst.id to i }
        var credit = 0L

        fun pourForward(startIdx: Int, amount: Long, pdate: LocalDate) {
            var remaining = amount
            var idx = startIdx
            while (remaining > 0 && idx < n) {
                val room = insts[idx].amountMinor - filled[idx]
                if (room > 0) {
                    val applied = minOf(room, remaining)
                    filled[idx] += applied
                    contribs[idx].add(applied to pdate)
                    remaining -= applied
                }
                idx++
            }
            if (remaining > 0) credit += remaining
        }

        // Pass 1 — earmarks (target first, overflow cascades forward). An
        // earmark whose target is not in the active version falls through to
        // the waterfall (historical intent only).
        val waterfall = mutableListOf<Payment>()
        for (p in pays) {
            val targetIdx = p.earmarkedInstallmentId?.let { indexById[it] }
            if (targetIdx == null) {
                waterfall.add(p)
                continue
            }
            pourForward(targetIdx, p.amountMinor, p.date)
        }

        // Pass 2 — waterfall, oldest-unfilled-first.
        for (p in waterfall) {
            pourForward(0, p.amountMinor, p.date)
        }

        val fills = ArrayList<InstallmentFill>(n)
        var arrears = 0L
        for ((idx, inst) in insts.withIndex()) {
            val scheduled = inst.amountMinor
            val amountFilled = filled[idx]
            val onTime = contribs[idx]
                .filter { !it.second.isAfter(inst.dueDate) }
                .sumOf { it.first }
            val shortfall = maxOf(0L, scheduled - amountFilled)

            val state = when {
                amountFilled == 0L -> InstallmentState.UNPAID
                amountFilled < scheduled -> InstallmentState.PARTIAL
                inst.dueDate.isAfter(asOf) -> InstallmentState.FILLED_AHEAD
                onTime >= scheduled -> InstallmentState.FILLED
                else -> InstallmentState.FILLED_LATE
            }

            if (!inst.dueDate.isAfter(asOf)) arrears += shortfall

            fills.add(
                InstallmentFill(
                    sequence = inst.sequence,
                    installmentId = inst.id,
                    dueDate = inst.dueDate,
                    scheduledMinor = scheduled,
                    filledMinor = amountFilled,
                    shortfallMinor = shortfall,
                    onTimeCoverageMinor = onTime,
                    state = state,
                ),
            )
        }

        return AllocationResult(fills = fills, creditMinor = credit, arrearsMinor = arrears)
    }
}
