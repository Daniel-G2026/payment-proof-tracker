package com.danielanazodo.proofledger.ui.history

import com.danielanazodo.proofledger.data.remote.dto.AuditEventDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryMapperTest {

    private fun event(
        id: String,
        action: String,
        entityType: String = "transaction",
        old: Map<String, Any?>? = null,
        new: Map<String, Any?>? = null,
    ) = AuditEventDto(
        id = id,
        businessId = "b1",
        actorId = "u1",
        entityType = entityType,
        entityId = "e1",
        action = action,
        status = "success",
        oldValues = old,
        newValues = new,
        createdAt = "2026-06-05T10:00:00+00:00",
    )

    @Test
    fun `update maps changed fields with friendly labels and formatting`() {
        val entries = EditHistoryMapper.toEntries(
            listOf(
                event(
                    "1",
                    "update",
                    // amount arrives as Double from JSON
                    old = mapOf("amount_minor" to 100000.0, "note" to "old note"),
                    new = mapOf("amount_minor" to 250000.0, "note" to "new note"),
                ),
            ),
        )
        val entry = entries.single()
        assertEquals("Edited", entry.title)
        val amount = entry.changes.single { it.label == "Amount" }
        // 100000 minor -> ₦1,000.00 ; 250000 -> ₦2,500.00
        assertTrue(amount.oldValue!!.contains("1,000"))
        assertTrue(amount.newValue!!.contains("2,500"))
        val note = entry.changes.single { it.label == "Note" }
        assertEquals("old note", note.oldValue)
        assertEquals("new note", note.newValue)
    }

    @Test
    fun `direction is humanized`() {
        val entry = EditHistoryMapper.toEntries(
            listOf(event("1", "update", old = mapOf("direction" to "money_in"), new = mapOf("direction" to "money_out"))),
        ).single()
        val type = entry.changes.single { it.label == "Type" }
        assertEquals("Money in", type.oldValue)
        assertEquals("Money out", type.newValue)
    }

    @Test
    fun `create shows snapshot fields with no old value`() {
        val entry = EditHistoryMapper.toEntries(
            listOf(
                event(
                    "1",
                    "create",
                    new = mapOf("amount_minor" to 5000.0, "counterparty_name" to "Ada", "currency" to "NGN"),
                ),
            ),
        ).single()
        assertEquals("Created", entry.title)
        // currency has no friendly label -> excluded; who + amount included.
        assertTrue(entry.changes.any { it.label == "Who" && it.oldValue == null && it.newValue == "Ada" })
        assertTrue(entry.changes.any { it.label == "Amount" })
        assertTrue(entry.changes.none { it.label == "currency" })
    }

    @Test
    fun `delete has a title and no field rows`() {
        val entry = EditHistoryMapper.toEntries(
            listOf(event("1", "delete", old = mapOf("amount_minor" to 5000.0))),
        ).single()
        assertEquals("Deleted", entry.title)
        assertTrue(entry.changes.isEmpty())
    }

    @Test
    fun `counterparty entity uses its own labels`() {
        val entry = EditHistoryMapper.toEntries(
            listOf(
                event(
                    "1",
                    "update",
                    entityType = "counterparty",
                    old = mapOf("name" to "Old", "phone" to "111"),
                    new = mapOf("name" to "New", "phone" to "222"),
                ),
            ),
        ).single()
        assertTrue(entry.changes.any { it.label == "Name" })
        assertTrue(entry.changes.any { it.label == "Phone" })
    }
}
