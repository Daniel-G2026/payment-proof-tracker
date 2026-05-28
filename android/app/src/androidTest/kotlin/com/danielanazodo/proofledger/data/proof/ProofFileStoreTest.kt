package com.danielanazodo.proofledger.data.proof

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProofFileStoreTest {
    @Test
    fun prepare_producesStableSha256ForIdenticalBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "proof-hash-test").apply { mkdirs() }
        val first = File(dir, "one.jpg").apply { writeBytes(BYTES) }
        val second = File(dir, "two.jpg").apply { writeBytes(BYTES) }
        val different = File(dir, "different.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val store = ProofFileStore(context)

        val one = store.prepare(Uri.fromFile(first), "one")
        val two = store.prepare(Uri.fromFile(second), "two")
        val other = store.prepare(Uri.fromFile(different), "other")

        assertEquals(one.imageSha256, two.imageSha256)
        assertNotEquals(one.imageSha256, other.imageSha256)
        assertEquals(64, one.imageSha256.length)
        assertTrue(one.originalSizeBytes > 0)
    }

    private companion object {
        val BYTES = byteArrayOf(
            0xff.toByte(),
            0xd8.toByte(),
            0xff.toByte(),
            0xe0.toByte(),
            80,
            114,
            111,
            111,
            102,
        )
    }
}
