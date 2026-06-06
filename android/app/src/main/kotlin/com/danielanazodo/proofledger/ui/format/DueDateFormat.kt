package com.danielanazodo.proofledger.ui.format

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val DUE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

/** Short due-date label, e.g. "20 Jun". */
fun formatDueDate(date: LocalDate): String = date.format(DUE_DATE_FORMAT)

/**
 * Friendly countdown relative to [today]:
 * "due today", "in 3 days", "in 2 weeks", "3 days overdue", "2 weeks overdue".
 * Under a week shows days; a week or more rounds to weeks.
 */
fun relativeDueLabel(dueDate: LocalDate, today: LocalDate = LocalDate.now()): String {
    val days = ChronoUnit.DAYS.between(today, dueDate)
    return when {
        days == 0L -> "due today"
        days > 0 -> "in ${humanSpan(days)}"
        else -> "${humanSpan(-days)} overdue"
    }
}

private fun humanSpan(days: Long): String =
    if (days < 7) {
        "$days ${plural(days, "day")}"
    } else {
        val weeks = (days + 3) / 7 // round to nearest week
        "$weeks ${plural(weeks, "week")}"
    }

private fun plural(n: Long, unit: String): String = if (n == 1L) unit else "${unit}s"
