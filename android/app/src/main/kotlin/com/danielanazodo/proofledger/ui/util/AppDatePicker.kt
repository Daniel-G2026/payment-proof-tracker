package com.danielanazodo.proofledger.ui.util

import android.app.DatePickerDialog
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Opens the platform date picker seeded to [currentIsoDate] (YYYY-MM-DD) and
 * calls [onDateSelected] with the picked ISO date. Shared so every form picks
 * dates the same way — constrained inputs use a picker, never free text.
 */
fun showAppDatePicker(
    context: Context,
    currentIsoDate: String,
    onDateSelected: (String) -> Unit,
    onDismiss: () -> Unit = {},
) {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val calendar = Calendar.getInstance()
    runCatching { format.parse(currentIsoDate) }.getOrNull()?.let { calendar.time = it }
    val dialog = DatePickerDialog(
        context,
        { _, year, month, day ->
            calendar.set(year, month, day)
            onDateSelected(format.format(calendar.time))
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH),
    )
    dialog.setOnDismissListener { onDismiss() }
    dialog.show()
}
