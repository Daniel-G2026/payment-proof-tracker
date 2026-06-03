package com.danielanazodo.proofledger.ui.format

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Whole-period units the summary/export supports — any week or any month. */
enum class PeriodUnit { WEEK, MONTH }

/**
 * An inclusive [startIso, endIso] date window the user can summarise or export.
 *
 * Periods are always whole weeks or whole months (product decision — no
 * free-form custom ranges). String ISO dates (`yyyy-MM-dd`) compare correctly
 * lexicographically, so range filtering is a simple `date in start..end`.
 */
data class ReportPeriod(
    val unit: PeriodUnit,
    val startIso: String,
    val endIso: String,
) {
    /** Canonical label, e.g. "June 2026" or "May 12–18, 2026". */
    val label: String
        get() = when (unit) {
            PeriodUnit.MONTH -> monthLabel(startIso)
            PeriodUnit.WEEK -> weekLabel(startIso, endIso)
        }

    fun previous(): ReportPeriod = shifted(-1)

    fun next(): ReportPeriod = shifted(+1)

    fun contains(dateIso: String): Boolean = dateIso in startIso..endIso

    private fun shifted(steps: Int): ReportPeriod {
        val cal = calendarFor(startIso)
        when (unit) {
            PeriodUnit.MONTH -> cal.add(Calendar.MONTH, steps)
            PeriodUnit.WEEK -> cal.add(Calendar.DAY_OF_MONTH, 7 * steps)
        }
        val anchor = isoOf(cal)
        return if (unit == PeriodUnit.MONTH) monthContaining(anchor) else weekContaining(anchor)
    }
}

/**
 * A label that prefers "This week"/"This month" when [todayIso] falls inside
 * the period, otherwise the canonical label. Keeps the picker friendly for the
 * common case without the user reading a date back to themselves.
 */
fun ReportPeriod.relativeLabel(todayIso: String = todayIsoDate()): String =
    if (contains(todayIso)) {
        when (unit) {
            PeriodUnit.MONTH -> "This month"
            PeriodUnit.WEEK -> "This week"
        }
    } else {
        label
    }

/** The period of [unit] that contains today. */
fun currentPeriod(unit: PeriodUnit, todayIso: String = todayIsoDate()): ReportPeriod =
    when (unit) {
        PeriodUnit.MONTH -> monthContaining(todayIso)
        PeriodUnit.WEEK -> weekContaining(todayIso)
    }

fun monthContaining(dateIso: String): ReportPeriod {
    val cal = calendarFor(dateIso)
    cal.set(Calendar.DAY_OF_MONTH, 1)
    val start = isoOf(cal)
    cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
    val end = isoOf(cal)
    return ReportPeriod(PeriodUnit.MONTH, start, end)
}

fun weekContaining(dateIso: String): ReportPeriod {
    val cal = calendarFor(dateIso)
    var diff = cal.get(Calendar.DAY_OF_WEEK) - cal.firstDayOfWeek
    if (diff < 0) diff += 7
    cal.add(Calendar.DAY_OF_MONTH, -diff)
    val start = isoOf(cal)
    cal.add(Calendar.DAY_OF_MONTH, 6)
    val end = isoOf(cal)
    return ReportPeriod(PeriodUnit.WEEK, start, end)
}

/**
 * Distinct periods of [unit] that contain at least one of [dates], newest
 * first, always including the current period so the user can return to "now"
 * even before there's a record in it. Powers the jump-to-any picker.
 */
fun periodsCovering(
    unit: PeriodUnit,
    dates: List<String>,
    todayIso: String = todayIsoDate(),
): List<ReportPeriod> {
    val periods = LinkedHashMap<String, ReportPeriod>()
    val current = currentPeriod(unit, todayIso)
    periods[current.startIso] = current
    for (date in dates) {
        if (date.isBlank()) continue
        val period = if (unit == PeriodUnit.MONTH) monthContaining(date) else weekContaining(date)
        periods[period.startIso] = period
    }
    return periods.values.sortedByDescending { it.startIso }
}

private val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private val monthLabelFormat = SimpleDateFormat("MMMM yyyy", Locale.US)
private val dayMonthFormat = SimpleDateFormat("MMM d", Locale.US)
private val dayMonthYearFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

private fun calendarFor(dateIso: String): Calendar =
    Calendar.getInstance().apply {
        val parsed = runCatching { isoFormat.parse(dateIso) }.getOrNull()
        if (parsed != null) time = parsed
    }

private fun isoOf(cal: Calendar): String = isoFormat.format(cal.time)

private fun monthLabel(startIso: String): String = runCatching {
    monthLabelFormat.format(isoFormat.parse(startIso)!!)
}.getOrDefault(startIso)

private fun weekLabel(startIso: String, endIso: String): String = runCatching {
    val start = isoFormat.parse(startIso)!!
    val end = isoFormat.parse(endIso)!!
    val startCal = Calendar.getInstance().apply { time = start }
    val endCal = Calendar.getInstance().apply { time = end }
    if (startCal.get(Calendar.MONTH) == endCal.get(Calendar.MONTH)) {
        // "May 12–18, 2026"
        val day = SimpleDateFormat("d", Locale.US).format(end)
        "${dayMonthFormat.format(start)}–$day, ${endCal.get(Calendar.YEAR)}"
    } else {
        // "May 28 – Jun 3, 2026"
        "${dayMonthFormat.format(start)} – ${dayMonthYearFormat.format(end)}"
    }
}.getOrDefault("$startIso – $endIso")
