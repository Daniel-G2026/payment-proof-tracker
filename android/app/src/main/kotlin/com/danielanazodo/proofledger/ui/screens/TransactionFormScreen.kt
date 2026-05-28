package com.danielanazodo.proofledger.ui.screens

import android.app.DatePickerDialog
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.ui.format.displayDate
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerFilterChip
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.components.QuietButton
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.TransactionFormViewModel
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun TransactionFormScreen(
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: TransactionFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var fullProofOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var datePickerOpen by remember { mutableStateOf(false) }
    val leaveForm = {
        viewModel.discardIntakeProof()
        onBack()
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pickerOpen = false
        if (uri != null) {
            viewModel.onProofSelected(uri.toString(), querySizeBytes(context, uri))
        }
    }
    val openImagePicker = rememberDebouncedClick(windowMs = 1000L) {
        if (!pickerOpen) {
            pickerOpen = true
            imagePicker.launch("image/*")
        }
    }
    val back = rememberDebouncedClick(onClick = leaveForm)
    BackHandler(onBack = back)
    val openDatePicker = rememberDebouncedClick {
        if (!datePickerOpen) {
            datePickerOpen = true
            showDatePicker(
                context = context,
                currentIsoDate = state.transactionDate,
                onDateSelected = viewModel::onDateChange,
                onDismiss = { datePickerOpen = false },
            )
        }
    }
    val selectCounterparty = rememberDebouncedClick(onClick = viewModel::onCounterpartySelect)
    val addNewCounterparty = rememberDebouncedClick(onClick = viewModel::onAddNewCounterparty)
    val openFullProof = rememberDebouncedClick { fullProofOpen = true }
    val removeProof = rememberDebouncedClick(onClick = viewModel::onProofRemoved)
    val save = rememberDebouncedClick(onClick = viewModel::save)
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    ProofLedgerScreen(
        title = if (state.transactionId == null) "Add record" else "Edit record",
        onBack = back,
    ) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Phase 8 banner stack. Three mutually-exclusive states:
            //   1. ocrRunning && !ocrDraftApplied   → "Drafting from your screenshot…"
            //   2. serverDraftError != null         → "Couldn't read this one — type the fields yourself."
            //   3. ocrDraftApplied                  → "Draft ready — please review."
            // None of these block the form; the user can always type instead.
            when {
                state.ocrRunning && !state.ocrDraftApplied -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Drafting from your screenshot…",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                "We're reading the proof so we can pre-fill the form. You can keep typing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                state.serverDraftError != null -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Draft unavailable",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                state.serverDraftError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            QuietButton(
                                text = "Dismiss",
                                onClick = viewModel::onServerDraftErrorDismissed,
                            )
                        }
                    }
                }
                state.ocrDraftApplied -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Draft ready",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                "We filled in what we could read. Please review before saving.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProofLedgerFilterChip(
                    selected = state.direction == "money_in",
                    onClick = { viewModel.onDirectionChange("money_in") },
                    label = "Money in",
                )
                ProofLedgerFilterChip(
                    selected = state.direction == "money_out",
                    onClick = { viewModel.onDirectionChange("money_out") },
                    label = "Money out",
                )
            }
            ProofLedgerTextField(
                value = state.amount,
                onValueChange = viewModel::onAmountChange,
                label = "Amount",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            QuietButton(
                text = "Date: ${displayDate(state.transactionDate)}",
                onClick = openDatePicker,
            )
            ProofLedgerTextField(
                value = state.counterpartyQuery,
                onValueChange = viewModel::onCounterpartyQueryChange,
                label = if (state.direction == "money_in") "Who paid?" else "Who you paid?",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.suggestions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.suggestions.forEach { counterparty ->
                        TextButton(
                            onClick = { selectCounterparty(counterparty) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("${counterparty.name} - ${counterpartyTypeLabel(counterparty.type)}")
                        }
                    }
                }
            }
            if (state.counterpartyQuery.isNotBlank() && state.counterpartyId == null) {
                AssistChip(
                    onClick = addNewCounterparty,
                    label = { Text("Save as new person") },
                )
            }
            if (state.addingNewCounterparty) {
                Text("Person type", style = MaterialTheme.typography.labelMedium)
                CounterpartyTypeChips(
                    selectedType = state.newCounterpartyType,
                    onTypeChange = { type -> type?.let(viewModel::onNewCounterpartyTypeChange) },
                    includeAll = false,
                )
            }
            ProofLedgerTextField(
                value = state.category,
                onValueChange = viewModel::onCategoryChange,
                label = "Category",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ProofLedgerTextField(
                value = state.bankName,
                onValueChange = viewModel::onBankNameChange,
                label = "Bank or provider",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ProofLedgerTextField(
                value = state.referenceNumber,
                onValueChange = viewModel::onReferenceNumberChange,
                label = "Reference number",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ProofLedgerTextField(
                value = state.note,
                onValueChange = viewModel::onNoteChange,
                label = "Note",
                singleLine = false,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Proof", style = MaterialTheme.typography.labelMedium)
            if (state.proofUri == null) {
                QuietButton(
                    text = "Attach screenshot proof",
                    onClick = openImagePicker,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Filled.Image,
                )
            } else {
                GroupedPanel {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AsyncImage(
                            model = state.proofUri,
                            contentDescription = "Payment proof preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clickable(onClick = openFullProof),
                        )
                        Text(
                            "Estimated upload: ${formatBytes(state.proofOriginalSizeBytes ?: 0L)} or less after compression",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuietButton(
                                text = "Change",
                                onClick = openImagePicker,
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.Image,
                            )
                            TextButton(onClick = removeProof) {
                                Text("Remove")
                            }
                        }
                    }
                }
            }
            if (fullProofOpen && state.proofUri != null) {
                Dialog(onDismissRequest = { fullProofOpen = false }) {
                    Card {
                        AsyncImage(
                            model = state.proofUri,
                            contentDescription = "Full payment proof",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            PrimaryActionButton(
                text = if (state.loading) "Saving..." else "Save record",
                onClick = save,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Filled.Check,
            )
        }
    }
}

private fun showDatePicker(
    context: android.content.Context,
    currentIsoDate: String,
    onDateSelected: (String) -> Unit,
    onDismiss: () -> Unit,
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

private fun querySizeBytes(context: android.content.Context, uri: Uri): Long? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst() && index >= 0) cursor.getLong(index) else null
    }

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "unknown"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.toInt().coerceAtLeast(1)} KB"
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}
