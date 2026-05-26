package com.danielanazodo.proofledger.data.ocr

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Heuristic parser that turns raw OCR text from a Nigerian payment receipt
 * into an [OcrDraft]. Pure Kotlin — no Android deps — so it's unit-testable.
 *
 * Philosophy: only suggest fields we're reasonably confident about. A wrong
 * pre-fill costs the user more than a blank field, because they have to spot
 * and correct it instead of just typing.
 */
@Singleton
class OcrDraftParser @Inject constructor() {

    fun parse(rawText: String): OcrDraft = OcrDraft(
        amountMinor = findAmountMinor(rawText),
        dateIso = findDateIso(rawText),
        reference = findReference(rawText),
        bankName = findBank(rawText),
        rawText = rawText,
    )

    // --- amount ---------------------------------------------------------

    // Matches things that *look* like a money amount on a receipt: either a
    // currency-prefixed number (₦8,000,000.00 / NGN 1,500 / N 200.50), a
    // comma-grouped number (8,000,000.00), or a decimal-pointed number
    // (200.50). Bare digit runs are deliberately excluded — on a Nigerian
    // bank receipt they are virtually always account numbers, reference
    // codes, or phone numbers, never the transaction amount.
    //
    // Three alternation branches, each with its own capture group; the
    // caller picks whichever group matched.
    private val amountRegex = Regex(
        "(?i)(?:₦|NGN|N\\b)\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)" +
            "|(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?)" +
            "|(\\d+\\.\\d{2})",
    )

    // Labels that strongly indicate the *transaction* amount (not a balance
    // or fee). When present we prefer the number closest after the label.
    //
    // The gap allows any non-digit characters (including stray `N` or `₦`
    // glyphs OCR may have lifted off the currency symbol) so we can reach
    // the digits even when they sit on the line below the label and are
    // jammed against noise like "N78000000Credit". Once a label has anchored
    // us, we also accept a bare digit run with no separators — banks
    // sometimes render large amounts without commas or decimals.
    private val amountLabelRegex = Regex(
        """(?i)\b(amount|amt|paid|transfer|sent|received|debit|credit)\b[^\d]{0,16}((?:₦|NGN|N)?\s*\d[\d,]*(?:\.\d{1,2})?)""",
    )

    internal fun findAmountMinor(text: String): Long? {
        // Prefer labeled amounts when we can find one.
        amountLabelRegex.findAll(text)
            .mapNotNull { match -> toMinor(match.groupValues[2]) }
            .firstOrNull()
            ?.let { return it }

        // Otherwise fall back to "biggest plausible *formatted* number in
        // the text" — receipt amounts are almost always the largest figure
        // shown AND are written with a currency prefix, comma grouping, or
        // a decimal. We deliberately ignore plain digit runs (account /
        // reference / phone numbers) and tiny values under ₦100.
        val candidates = amountRegex.findAll(text)
            .mapNotNull { match ->
                // Three alternation branches, one capture each — pick the
                // one that actually matched.
                val raw = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
                raw?.let(::toMinor)
            }
            .filter { it >= 10_000L } // at least ₦100
            .toList()
        return candidates.maxOrNull()
    }

    private fun toMinor(raw: String): Long? {
        // Strip currency prefix (₦, NGN, leading "N ") and separators before
        // parsing — the amount regex captures these so the matched substring
        // is human-readable, but the numeric conversion needs digits only.
        val cleaned = raw
            .replace("₦", "")
            .replace(Regex("(?i)NGN"), "")
            .replace(Regex("(?i)^\\s*N\\b"), "")
            .replace(",", "")
            .replace(" ", "")
            .trim()
        if (cleaned.isBlank()) return null
        val parts = cleaned.split(".")
        if (parts.size > 2) return null
        val whole = parts[0].toLongOrNull() ?: return null
        if (whole < 0) return null
        val fraction = parts.getOrNull(1).orEmpty()
        if (fraction.length > 2 || fraction.any { !it.isDigit() }) return null
        val paddedFraction = fraction.padEnd(2, '0').ifBlank { "00" }.toLong()
        // Cap before multiplying so we can't overflow Long for absurd OCR noise.
        if (whole > 999_999_999_999L) return null
        return whole * 100 + paddedFraction
    }

    // --- date -----------------------------------------------------------

    // Common Nigerian receipt formats, ordered by specificity.
    private val dateFormats: List<SimpleDateFormat> = listOf(
        SimpleDateFormat("yyyy-MM-dd", Locale.US),
        SimpleDateFormat("dd/MM/yyyy", Locale.US),
        SimpleDateFormat("dd-MM-yyyy", Locale.US),
        SimpleDateFormat("dd MMM yyyy", Locale.US),
        SimpleDateFormat("MMM dd, yyyy", Locale.US),
        SimpleDateFormat("d MMM yyyy", Locale.US),
        SimpleDateFormat("d MMMM yyyy", Locale.US),
        SimpleDateFormat("MMMM d, yyyy", Locale.US),
    ).onEach { it.isLenient = false }

    private val dateCandidatePatterns = listOf(
        Regex("""\b\d{4}-\d{1,2}-\d{1,2}\b"""),
        Regex("""\b\d{1,2}/\d{1,2}/\d{4}\b"""),
        Regex("""\b\d{1,2}-\d{1,2}-\d{4}\b"""),
        Regex("""\b\d{1,2}\s+[A-Za-z]{3,}\s+\d{4}\b"""),
        Regex("""\b[A-Za-z]{3,}\s+\d{1,2},?\s+\d{4}\b"""),
    )

    private val outputDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    internal fun findDateIso(text: String): String? {
        val today = Calendar.getInstance()
        for (pattern in dateCandidatePatterns) {
            for (match in pattern.findAll(text)) {
                val candidate = match.value
                for (fmt in dateFormats) {
                    val parsed = runCatching { fmt.parse(candidate) }.getOrNull() ?: continue
                    val cal = Calendar.getInstance().apply { time = parsed }
                    // Reject obvious garbage: year < 2000 or more than a year
                    // in the future.
                    val year = cal.get(Calendar.YEAR)
                    if (year < 2000) continue
                    val maxFuture = today.clone() as Calendar
                    maxFuture.add(Calendar.YEAR, 1)
                    if (cal.after(maxFuture)) continue
                    return outputDateFormat.format(parsed)
                }
            }
        }
        return null
    }

    // --- reference ------------------------------------------------------

    // Matches a label word (case-insensitive), optionally followed by a
    // descriptor like "Number" / "No." / "ID" / "Code", then any mix of
    // whitespace / colons / dashes / newlines, then the code itself. This
    // shape handles the common Nigerian receipt layout where the value sits
    // on the line *below* the label, including variants like:
    //   Reference Number
    //   TRF20260524ABC123
    // Matches a label word (case-insensitive), optionally followed by a
    // descriptor like "Number" / "No." / "ID" / "Code", then any mix of
    // whitespace / colons / dashes / newlines, then the code itself. This
    // shape handles the common Nigerian receipt layout where the value sits
    // on the line *below* the label, including variants like:
    //   Reference Number
    //   TRF20260524ABC123
    // The case-insensitive flag is *scoped* to just the label and descriptor.
    // Applying (?i) to the whole regex would silently turn the code-capture's
    // [A-Z0-9] into [A-Za-z0-9], which would happily match plain English
    // words like "Details" as a 7-character "reference code".
    private val referenceRegex = Regex(
        "(?i:\\b(?:ref(?:erence)?|txn|transaction|session|rrn|trace|confirmation)\\b)" +
            "(?i:\\s+(?:number|no\\.?|id|code))?" +
            "[\\s:\\-#.]{0,12}" +
            "([A-Z0-9][A-Z0-9\\-_/]{5,47})",
    )

    internal fun findReference(text: String): String? {
        val match = referenceRegex.find(text) ?: return null
        return match.groupValues[1].trim().takeIf { it.length in 6..48 }
    }

    // --- bank -----------------------------------------------------------

    // Small allowlist of common Nigerian banks / wallets. Case-insensitive
    // word-boundary match. Ordered so longer multi-word names match before
    // their shorter substrings.
    private val knownBanks = listOf(
        "First Bank",
        "Stanbic IBTC",
        "Sterling Bank",
        "Union Bank",
        "Wema Bank",
        "Polaris Bank",
        "Fidelity Bank",
        "Heritage Bank",
        "Keystone Bank",
        "Ecobank",
        "Zenith",
        "GTBank",
        "GTCO",
        "Access",
        "UBA",
        "FCMB",
        "Opay",
        "Kuda",
        "PalmPay",
        "Moniepoint",
        "Carbon",
        "VBank",
        "ALAT",
    )

    internal fun findBank(text: String): String? {
        for (name in knownBanks) {
            val pattern = Regex("(?i)\\b" + Regex.escape(name) + "\\b")
            if (pattern.containsMatchIn(text)) return name
        }
        return null
    }
}
