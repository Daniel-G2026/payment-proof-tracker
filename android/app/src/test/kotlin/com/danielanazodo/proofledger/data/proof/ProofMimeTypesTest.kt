package com.danielanazodo.proofledger.data.proof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProofMimeTypesTest {
    @Test
    fun `sniffs jpeg png and webp magic bytes`() {
        assertEquals(
            "image/jpeg",
            ProofMimeTypes.fromHeader(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)),
        )
        assertEquals(
            "image/png",
            ProofMimeTypes.fromHeader(
                byteArrayOf(
                    0x89.toByte(),
                    'P'.code.toByte(),
                    'N'.code.toByte(),
                    'G'.code.toByte(),
                    0x0D,
                    0x0A,
                    0x1A,
                    0x0A,
                )
            ),
        )
        assertEquals(
            "image/webp",
            ProofMimeTypes.fromHeader(
                byteArrayOf(
                    'R'.code.toByte(),
                    'I'.code.toByte(),
                    'F'.code.toByte(),
                    'F'.code.toByte(),
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    'W'.code.toByte(),
                    'E'.code.toByte(),
                    'B'.code.toByte(),
                    'P'.code.toByte(),
                )
            ),
        )
    }

    @Test
    fun `normalizes jpeg alias and rejects generic image mime`() {
        assertEquals("image/jpeg", ProofMimeTypes.normalizeAllowed("image/jpg"))
        assertNull(ProofMimeTypes.normalizeAllowed("image/*"))
    }

    @Test
    fun `maps allowed mime types to upload extensions`() {
        assertEquals(".jpg", ProofMimeTypes.extensionFor("image/jpeg"))
        assertEquals(".png", ProofMimeTypes.extensionFor("image/png"))
        assertEquals(".webp", ProofMimeTypes.extensionFor("image/webp"))
        assertEquals(".pdf", ProofMimeTypes.extensionFor("application/pdf"))
    }

    @Test
    fun `pdf is only accepted when allowPdf is set`() {
        val pdfHeader = byteArrayOf(
            '%'.code.toByte(),
            'P'.code.toByte(),
            'D'.code.toByte(),
            'F'.code.toByte(),
            '-'.code.toByte(),
            '1'.code.toByte(),
            '.'.code.toByte(),
            '4'.code.toByte(),
        )

        // Default (image-only) path — the payment-proof + share-sheet callers.
        assertNull(ProofMimeTypes.fromHeader(pdfHeader))
        assertNull(ProofMimeTypes.normalizeAllowed("application/pdf"))
        assertNull(ProofMimeTypes.fromPath("offer-letter.pdf"))

        // Offer-letter path opts in.
        assertEquals("application/pdf", ProofMimeTypes.fromHeader(pdfHeader, allowPdf = true))
        assertEquals("application/pdf", ProofMimeTypes.normalizeAllowed("application/pdf", allowPdf = true))
        assertEquals("application/pdf", ProofMimeTypes.fromPath("offer-letter.pdf", allowPdf = true))
    }

    @Test
    fun `images still sniff correctly even when allowPdf is set`() {
        assertEquals(
            "image/jpeg",
            ProofMimeTypes.fromHeader(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00),
                allowPdf = true,
            ),
        )
    }
}
