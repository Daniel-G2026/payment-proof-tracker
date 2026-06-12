package com.danielanazodo.proofledger.data.remote

import com.danielanazodo.proofledger.data.remote.dto.UnitUpdateDto
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * Moshi omits null fields by default, so a *cleared* nullable column (e.g. a
 * unit's buyer) is dropped from the PATCH body — and the backend reads "field
 * omitted" as "leave unchanged" (`exclude_unset`). The removal then never
 * reaches the server and reappears on a fresh device.
 *
 * This factory makes **only [UnitUpdateDto]** serialize its nulls, so an
 * explicit `buyer_counterparty_id: null` is sent and the backend clears it.
 * It is scoped to this one DTO on purpose: every other endpoint keeps the
 * "null = leave unchanged" contract (e.g. a name-only profile edit must not
 * wipe the phone). Unit updates always re-send the whole current row, so the
 * only nulls on the wire are fields that are genuinely empty.
 */
object UnitUpdateNullSerializationFactory : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: Set<Annotation>,
        moshi: Moshi,
    ): JsonAdapter<*>? {
        if (Types.getRawType(type) != UnitUpdateDto::class.java) return null
        val delegate: JsonAdapter<UnitUpdateDto> = moshi.nextAdapter(this, type, annotations)
        return object : JsonAdapter<UnitUpdateDto>() {
            override fun fromJson(reader: JsonReader): UnitUpdateDto? = delegate.fromJson(reader)

            override fun toJson(writer: JsonWriter, value: UnitUpdateDto?) {
                val previous = writer.serializeNulls
                writer.serializeNulls = true
                try {
                    delegate.toJson(writer, value)
                } finally {
                    writer.serializeNulls = previous
                }
            }
        }
    }
}
