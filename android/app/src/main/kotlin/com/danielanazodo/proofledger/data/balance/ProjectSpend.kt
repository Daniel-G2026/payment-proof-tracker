package com.danielanazodo.proofledger.data.balance

/**
 * Per-project actual-spend rollup (Phase 13 Cut 5). Pure + testable, mirroring
 * the other balance calculators. Actual spend only — no budget/variance (the
 * locked Cut 5 decision). Groups a project's money-out by category snapshot.
 */
data class ProjectSpendLine(val category: String, val totalMinor: Long)

data class ProjectSpend(
    val totalMinor: Long,
    val byCategory: List<ProjectSpendLine>,
)

object ProjectSpendCalculator {
    const val UNCATEGORIZED = "Uncategorised"

    /** [items] = (category snapshot, amountMinor) for each money-out txn. */
    fun summarize(items: List<Pair<String?, Long>>): ProjectSpend {
        val byCategory = items
            .groupBy { (category, _) -> category?.trim()?.ifBlank { null } ?: UNCATEGORIZED }
            .map { (category, rows) -> ProjectSpendLine(category, rows.sumOf { it.second }) }
            .sortedWith(compareByDescending<ProjectSpendLine> { it.totalMinor }.thenBy { it.category })
        return ProjectSpend(totalMinor = items.sumOf { it.second }, byCategory = byCategory)
    }
}
