package com.danielanazodo.proofledger.ui.history

import com.danielanazodo.proofledger.data.remote.dto.AuditEventDto
import com.danielanazodo.proofledger.ui.format.displayDate
import com.danielanazodo.proofledger.ui.format.formatNaira

/** A single field that changed, in plain language. Null = "not set". */
data class FieldChange(
    val label: String,
    val oldValue: String?,
    val newValue: String?,
)

/** One entry in an entity's edit history, ready to render. */
data class EditHistoryEntry(
    val id: String,
    val action: String,
    val title: String,
    val timestampIso: String,
    val changes: List<FieldChange>,
)

/**
 * Pure mapping from raw audit DTOs to display-ready history entries. Keeps all
 * the field-name → friendly-label + value-formatting logic in one testable
 * place (no Android, no I/O). Numbers arrive from JSON as Double, so coercion
 * goes through [asLong].
 */
object EditHistoryMapper {

    private val TRANSACTION_LABELS = mapOf(
        "amount_minor" to "Amount",
        "direction" to "Type",
        "transaction_date" to "Date",
        "counterparty_name" to "Who",
        "category" to "Category",
        "note" to "Note",
        "bank_name" to "Bank",
        "reference_number" to "Reference",
    )

    private val COUNTERPARTY_LABELS = mapOf(
        "name" to "Name",
        "type" to "Kind",
        "phone" to "Phone",
        "notes" to "Notes",
    )

    fun toEntries(events: List<AuditEventDto>): List<EditHistoryEntry> =
        events.map { toEntry(it) }

    private fun toEntry(event: AuditEventDto): EditHistoryEntry {
        val labels = labelsFor(event.entityType)
        val changes = when (event.action) {
            "create" -> snapshotChanges(event.newValues, labels)
            "delete" -> emptyList()
            else -> diffChanges(event.oldValues, event.newValues, labels)
        }
        return EditHistoryEntry(
            id = event.id,
            action = event.action,
            title = titleFor(event.action),
            timestampIso = event.createdAt,
            changes = changes,
        )
    }

    private fun labelsFor(entityType: String): Map<String, String> = when (entityType) {
        "counterparty" -> COUNTERPARTY_LABELS
        else -> TRANSACTION_LABELS
    }

    private fun titleFor(action: String): String = when (action) {
        "create" -> "Created"
        "delete" -> "Deleted"
        else -> "Edited"
    }

    /** For an edit: one FieldChange per changed, known field. */
    private fun diffChanges(
        oldValues: Map<String, Any?>?,
        newValues: Map<String, Any?>?,
        labels: Map<String, String>,
    ): List<FieldChange> {
        val keys = (oldValues.orEmpty().keys + newValues.orEmpty().keys)
        return labels.keys.filter { it in keys }.map { key ->
            FieldChange(
                label = labels.getValue(key),
                oldValue = format(key, oldValues?.get(key)),
                newValue = format(key, newValues?.get(key)),
            )
        }
    }

    /** For a create: the meaningful fields the entity started with. */
    private fun snapshotChanges(
        newValues: Map<String, Any?>?,
        labels: Map<String, String>,
    ): List<FieldChange> =
        labels.keys.mapNotNull { key ->
            val formatted = format(key, newValues?.get(key)) ?: return@mapNotNull null
            FieldChange(label = labels.getValue(key), oldValue = null, newValue = formatted)
        }

    private fun format(key: String, value: Any?): String? {
        if (value == null) return null
        return when (key) {
            "amount_minor" -> asLong(value)?.let { formatNaira(it) } ?: value.toString()
            "direction" -> if (value == "money_out") "Money out" else "Money in"
            "transaction_date" -> displayDate(value.toString())
            else -> value.toString().ifBlank { null }
        }
    }

    private fun asLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}
