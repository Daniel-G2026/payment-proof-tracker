package com.danielanazodo.proofledger.ui.format

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private val compactNumberFormat = NumberFormat.getNumberInstance(Locale.US).apply {
    maximumFractionDigits = 1
}

fun todayIsoDate(): String = isoDateFormat.format(Calendar.getInstance().time)

fun monthPrefix(dateIso: String = todayIsoDate()): String = dateIso.take(7)

/**
 * Returns the [start, end] ISO dates (inclusive) for the calendar week
 * containing today, using the device locale's first day of week.
 */
fun currentWeekRange(): Pair<String, String> {
    val cal = Calendar.getInstance()
    val firstDayOfWeek = cal.firstDayOfWeek
    var diff = cal.get(Calendar.DAY_OF_WEEK) - firstDayOfWeek
    if (diff < 0) diff += 7
    cal.add(Calendar.DAY_OF_MONTH, -diff)
    val start = isoDateFormat.format(cal.time)
    cal.add(Calendar.DAY_OF_MONTH, 6)
    val end = isoDateFormat.format(cal.time)
    return start to end
}

fun displayDate(dateIso: String): String = runCatching {
    val parsed = isoDateFormat.parse(dateIso) ?: return dateIso
    SimpleDateFormat("MMM d, yyyy", Locale.US).format(parsed)
}.getOrDefault(dateIso)

fun formatNaira(amountMinor: Long): String {
    val naira = amountMinor / 100.0
    return NumberFormat.getCurrencyInstance(Locale("en", "NG")).format(naira)
}

// ₦999,999,999,999.99 — well below Long.MAX_VALUE (9.2 * 10^18 minor) so the
// `whole * 100` multiplication can't silently wrap on the way to the result.
// Also mirrors the backend cap in MAX_AMOUNT_MINOR.
private const val MAX_WHOLE_NAIRA = 999_999_999_999L

fun formatCompactCount(count: Int): String {
    val value = count.coerceAtLeast(0)
    return when {
        value >= 1_000_000 -> "${compactNumberFormat.format(value / 1_000_000.0)}m"
        value >= 1_000 -> "${compactNumberFormat.format(value / 1_000.0)}k"
        else -> value.toString()
    }
}

fun parseNairaToMinor(input: String): Long? {
    val cleaned = input.replace(",", "").trim()
    if (cleaned.isBlank()) return null
    val parts = cleaned.split(".")
    if (parts.size > 2) return null
    val whole = parts[0].toLongOrNull() ?: return null
    val fraction = parts.getOrNull(1).orEmpty()
    if (whole < 0 || fraction.length > 2 || fraction.any { !it.isDigit() }) return null
    // Cap BEFORE the multiplication so we never overflow into a smaller
    // positive Long that the viewmodel's `<= 0` check would let through.
    if (whole > MAX_WHOLE_NAIRA) return null
    val paddedFraction = fraction.padEnd(2, '0').ifBlank { "00" }.toLong()
    return whole * 100 + paddedFraction
}

fun formatNairaInput(input: String): String {
    val cleaned = input.replace(",", "").trim()
    if (cleaned.isBlank()) return ""

    val dotIndex = cleaned.indexOf('.')
    val rawWhole = if (dotIndex >= 0) cleaned.take(dotIndex) else cleaned
    val wholeDigits = rawWhole.filter { it.isDigit() }
    val normalizedWhole = wholeDigits.trimStart('0').ifEmpty {
        if (wholeDigits.isNotEmpty() || dotIndex >= 0) "0" else ""
    }
    if (normalizedWhole.isBlank()) return ""

    val groupedWhole = normalizedWhole.reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()

    if (dotIndex < 0) return groupedWhole

    val fraction = cleaned
        .drop(dotIndex + 1)
        .filter { it.isDigit() }
        .take(2)
    return "$groupedWhole.$fraction"
}

fun formatNairaInput(amountMinor: Long): String {
    val whole = amountMinor / 100
    val fraction = amountMinor % 100
    val raw = if (fraction == 0L) {
        whole.toString()
    } else {
        "$whole.${fraction.toString().padStart(2, '0')}"
    }
    return formatNairaInput(raw)
}

fun signedNaira(amountMinor: Long, direction: String): String {
    val sign = if (direction == "money_out") "-" else "+"
    return "$sign${formatNaira(abs(amountMinor))}"
}
