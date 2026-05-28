package com.danielanazodo.proofledger.data.remote.dto

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenshotDtoTest {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun `parses orphan screenshot response with null transaction id`() {
        val json = """
            {
              "id": "00000000-0000-0000-0000-000000000001",
              "business_id": "00000000-0000-0000-0000-000000000002",
              "transaction_id": null,
              "uploaded_by": "00000000-0000-0000-0000-000000000003",
              "mime_type": "image/jpeg",
              "uploaded_file_size": 1234,
              "original_file_size": 2345,
              "width": 1080,
              "height": 1920,
              "storage_key": "proofs/example.jpg",
              "storage_status": "stored",
              "created_at": "2026-05-27T00:00:00Z",
              "updated_at": "2026-05-27T00:00:00Z",
              "deleted_at": null,
              "draft_status": "queued"
            }
        """.trimIndent()

        val dto = moshi.adapter(ScreenshotDto::class.java).fromJson(json)

        assertNull(dto!!.transactionId)
    }
}
