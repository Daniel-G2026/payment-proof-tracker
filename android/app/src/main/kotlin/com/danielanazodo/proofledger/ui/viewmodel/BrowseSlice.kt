package com.danielanazodo.proofledger.ui.viewmodel

/**
 * Shared helper for "browse lists that accumulate" (see the mobile dev doc).
 * Any on-screen list that only grows — units, projects, … — shows a bounded
 * **recent slice** by default and filters the **full set** once the user types
 * a query, so they never scroll a wall to find one item.
 *
 * Pure + deterministic so it's unit-testable without a ViewModel.
 */
data class BrowseSlice<T>(
    /** The rows to render: the recent slice when the query is blank, else all matches. */
    val visible: List<T>,
    /** Total non-deleted rows in the underlying list (not just the visible ones). */
    val total: Int,
    /** The trimmed query that produced [visible]. */
    val query: String,
) {
    /** Blank query and the recent cap hid some rows — show a "Showing X of Y" hint. */
    val truncated: Boolean get() = query.isEmpty() && total > visible.size
    /** The user searched and nothing matched. */
    val noMatches: Boolean get() = query.isNotEmpty() && visible.isEmpty()
    /** Worth showing the search field at all (list exceeds the cap, or mid-search). */
    val showSearch: Boolean get() = total > visible.size || query.isNotEmpty()
}

const val DEFAULT_BROWSE_RECENT_CAP = 5

/**
 * Build a [BrowseSlice]: filter [all] by [matches] when [query] is non-blank,
 * sort by [recencyKey] descending (newest first), and cap to [recentCap] only
 * when the query is blank (a search shows every match).
 */
fun <T> browseSlice(
    all: List<T>,
    query: String,
    recencyKey: (T) -> Long,
    matches: (T, String) -> Boolean,
    recentCap: Int = DEFAULT_BROWSE_RECENT_CAP,
): BrowseSlice<T> {
    val q = query.trim()
    val matched = if (q.isEmpty()) all else all.filter { matches(it, q) }
    val sorted = matched.sortedByDescending(recencyKey)
    val visible = if (q.isEmpty()) sorted.take(recentCap) else sorted
    return BrowseSlice(visible = visible, total = all.size, query = q)
}
