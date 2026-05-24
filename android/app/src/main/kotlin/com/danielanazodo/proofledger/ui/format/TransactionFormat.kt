package com.danielanazodo.proofledger.ui.format

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

fun todayIsoDate(): String = isoDateFormat.format(Calendar.getInstance().time)

fun monthPrefix(dateIso: String = todayIsoDate()): String = dateIso.take(7)

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

fun signedNaira(amountMinor: Long, direction: String): String {
    val sign = if (direction == "money_out") "-" else "+"
    return "$sign${formatNaira(abs(amountMinor))}"
}
