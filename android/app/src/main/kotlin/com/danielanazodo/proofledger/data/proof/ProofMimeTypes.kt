package com.danielanazodo.proofledger.data.proof

import java.io.File

object ProofMimeTypes {
    fun normalizeAllowed(value: String?): String? =
        when (value?.lowercase()) {
            "image/jpeg", "image/jpg" -> "image/jpeg"
            "image/png" -> "image/png"
            "image/webp" -> "image/webp"
            else -> null
        }

    fun fromPath(path: String?): String? =
        when (path?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            else -> null
        }

    fun fromHeader(bytes: ByteArray): String? =
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

            else -> null
        }

    fun fromFile(file: File): String? =
        file.inputStream().use { input ->
            val header = ByteArray(12)
            val read = input.read(header)
            if (read <= 0) null else fromHeader(header.copyOf(read))
        }

    fun extensionFor(mimeType: String): String =
        when (mimeType) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }

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
