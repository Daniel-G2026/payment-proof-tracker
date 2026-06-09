package com.danielanazodo.proofledger.data.proof

import java.io.File

object ProofMimeTypes {
    // PDF support is gated behind `allowPdf` (default false) so it stays scoped
    // to the offer-letter scan (Phase 13 Cut 6). The shared payment-proof and
    // share-sheet callers leave it off, keeping those paths strictly image-only.
    fun normalizeAllowed(value: String?, allowPdf: Boolean = false): String? =
        when (value?.lowercase()) {
            "image/jpeg", "image/jpg" -> "image/jpeg"
            "image/png" -> "image/png"
            "image/webp" -> "image/webp"
            "application/pdf" -> if (allowPdf) "application/pdf" else null
            else -> null
        }

    fun fromPath(path: String?, allowPdf: Boolean = false): String? =
        when (path?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            "pdf" -> if (allowPdf) "application/pdf" else null
            else -> null
        }

    fun fromHeader(bytes: ByteArray, allowPdf: Boolean = false): String? =
        when {
            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> "image/jpeg"

            bytes.size >= PNG_HEADER.size &&
                PNG_HEADER.indices.all { bytes[it] == PNG_HEADER[it] } -> "image/png"

            bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() &&
                bytes[11] == 'P'.code.toByte() -> "image/webp"

            allowPdf &&
                bytes.size >= PDF_HEADER.size &&
                PDF_HEADER.indices.all { bytes[it] == PDF_HEADER[it] } -> "application/pdf"

            else -> null
        }

    fun fromFile(file: File, allowPdf: Boolean = false): String? =
        file.inputStream().use { input ->
            val header = ByteArray(12)
            val read = input.read(header)
            if (read <= 0) null else fromHeader(header.copyOf(read), allowPdf)
        }

    fun extensionFor(mimeType: String): String =
        when (mimeType) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "application/pdf" -> ".pdf"
            else -> ".jpg"
        }

    // "%PDF-" — the standard PDF file signature.
    private val PDF_HEADER = byteArrayOf(
        '%'.code.toByte(),
        'P'.code.toByte(),
        'D'.code.toByte(),
        'F'.code.toByte(),
        '-'.code.toByte(),
    )

    private val PNG_HEADER = byteArrayOf(
        0x89.toByte(),
        'P'.code.toByte(),
        'N'.code.toByte(),
        'G'.code.toByte(),
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )
}
