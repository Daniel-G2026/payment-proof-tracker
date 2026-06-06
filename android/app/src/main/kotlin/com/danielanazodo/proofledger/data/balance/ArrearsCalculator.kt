package com.danielanazodo.proofledger.data.balance

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * Pure monthly-arrears aggregation (Phase 13 Cut 4). Mirrors the
 * `ReportRepository.summarize` pattern: no Room/Compose deps, fully unit-tested.
 * Implements the dashboard math in `docs/phase-13-allocation-contract.md` §4–§6.
 *
 * For each unit it pours the unit's payments into its active schedule with
 * [AllocationEngine], splits the resulting shortfall into **past-due** (`behind`)
 * and **this-month** by each installment's due date, then groups by buyer —
 * counting each **buyer once** even when they hold several due units.
 */
data class UnitScheduleData(
    val unitId: String,
    val unitLabel: String,
    val buyerId: String?,
    val buyerName: String?,
    val installments: List<Installment>,
    val payments: List<Payment>,
)

data class UnitArrears(
    val unitId: String,
    val unitLabel: String,
    val behindMinor: Long,
    val dueThisMonthMinor: Long,
) {
    val combinedMinor: Long get() = behindMinor + dueThisMonthMinor
}

data class BuyerArrears(
    val buyerId: String,
    val buyerName: String,
    val units: List<UnitArrears>,
    val dueThisMonth: Boolean,
    val fulfilledThisMonth: Boolean,
) {
    val behindMinor: Long get() = units.sumOf { it.behindMinor }
    val dueThisMonthMinor: Long get() = units.sumOf { it.dueThisMonthMinor }
    val combinedMinor: Long get() = behindMinor + dueThisMonthMinor
}

data class ArrearsDashboard(
    val month: YearMonth,
    val peopleDueThisMonth: Int,
    val peopleFulfilledThisMonth: Int,
    val combinedOutstandingMinor: Long,
    val buyers: List<BuyerArrears>,
)

object ArrearsCalculator {
    fun summarize(units: List<UnitScheduleData>, asOf: LocalDate): ArrearsDashboard {
        val monthStart = asOf.withDayOfMonth(1)
        val monthEnd = asOf.with(TemporalAdjusters.lastDayOfMonth())

        data class Computed(
            val data: UnitScheduleData,
            val behind: Long,
            val dueThisMonth: Long,
            val hasDueThisMonth: Boolean,
        )

        val computed = units.map { unit ->
            // Shortfall is asOf-independent; we run at monthEnd so every line due
            // by month-end is considered, then split by due date.
            val result = AllocationEngine.allocate(unit.installments, unit.payments, asOf = monthEnd)
            var behind = 0L
            var due = 0L
            var hasDue = false
            result.fills.forEach { fill ->
                when {
                    fill.dueDate.isBefore(monthStart) -> behind += fill.shortfallMinor
                    !fill.dueDate.isAfter(monthEnd) -> {
                        due += fill.shortfallMinor
                        hasDue = true
                    }
                    // due after month-end: a future line, ignored this month.
                }
            }
            Computed(unit, behind, due, hasDue)
        }

        // The money total sums across ALL units (including any without a buyer).
        val combinedTotal = computed.sumOf { it.behind + it.dueThisMonth }

        // People counts + the buyer list dedupe by buyer (counted once).
        val buyers = computed
            .filter { it.data.buyerId != null }
            .groupBy { it.data.buyerId!! }
            .map { (buyerId, list) ->
                val name = list.firstNotNullOfOrNull { it.data.buyerName } ?: "Buyer"
                val unitArrears = list.map {
                    UnitArrears(it.data.unitId, it.data.unitLabel, it.behind, it.dueThisMonth)
                }
                val dueThisMonth = list.any { it.hasDueThisMonth }
                val thisMonthShortfall = list.sumOf { it.dueThisMonth }
                BuyerArrears(
                    buyerId = buyerId,
                    buyerName = name,
                    units = unitArrears,
                    dueThisMonth = dueThisMonth,
                    // Fulfilled = due this month AND every due-this-month line filled.
                    fulfilledThisMonth = dueThisMonth && thisMonthShortfall == 0L,
                )
            }
            .sortedWith(compareByDescending<BuyerArrears> { it.combinedMinor }.thenBy { it.buyerName })

        return ArrearsDashboard(
            month = YearMonth.from(asOf),
            peopleDueThisMonth = buyers.count { it.dueThisMonth },
            peopleFulfilledThisMonth = buyers.count { it.fulfilledThisMonth },
            combinedOutstandingMinor = combinedTotal,
            buyers = buyers,
        )
    }
}
