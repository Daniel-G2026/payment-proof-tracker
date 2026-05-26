package com.danielanazodo.proofledger.data.ocr

/**
 * A best-effort draft extracted from on-device OCR text. Every field is a
 * suggestion — the user reviews and edits in the Add Transaction form before
 * saving. Null fields simply mean the parser couldn't find a confident match;
 * the form keeps whatever the user already typed.
 */
data class OcrDraft(
    /** Amount in minor units (kobo). Null when no plausible amount found. */
    val amountMinor: Long? = null,
    /** ISO yyyy-MM-dd. Null when no parseable date found. */
    val dateIso: String? = null,
    /** Reference / transaction id string. Null when not found. */
    val reference: String? = null,
    /** Detected bank or wallet name. Null when not recognized. */
    val bankName: String? = null,
    /** The raw OCR text the parser was given. Kept for persistence. */
    val rawText: String,
) {
    /** True when at least one field was extracted. */
    fun hasAnyField(): Boolean =
        amountMinor != null || dateIso != null || reference != null || bankName != null
}
