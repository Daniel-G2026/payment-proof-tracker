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
    }
}
