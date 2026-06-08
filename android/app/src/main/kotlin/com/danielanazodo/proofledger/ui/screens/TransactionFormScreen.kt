package com.danielanazodo.proofledger.ui.screens

import android.app.DatePickerDialog
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.ui.format.displayDate
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerFilterChip
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.components.QuietButton
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofAmber
import com.danielanazodo.proofledger.ui.theme.ProofBorder
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.theme.ProofLedgerDimens
import com.danielanazodo.proofledger.ui.theme.SurfaceCard
import com.danielanazodo.proofledger.ui.theme.SurfacePanel
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.TransactionFormViewModel
import coil.compose.AsyncImage
import com.danielanazodo.proofledger.data.repository.DuplicateCandidate
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
    // Picking a value from a suggestion/chip should end editing: clear focus so the
    // caret + keyboard don't linger and make the field look like it's still being
    // typed in (see docs/mobile-development-things-to-know.md).
    val focusManager = LocalFocusManager.current
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
    val selectCategory = rememberDebouncedClick(onClick = viewModel::onCategorySelect)
    val addCategory = rememberDebouncedClick(onClick = viewModel::onAddCategory)
    val openFullProof = rememberDebouncedClick { fullProofOpen = true }
    val removeProof = rememberDebouncedClick(onClick = viewModel::onProofRemoved)
    val save = rememberDebouncedClick(onClick = viewModel::save)
    val saveAnyway = rememberDebouncedClick(onClick = viewModel::saveAnyway)
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    ProofLedgerScreen(
        title = if (state.transactionId == null) "Add record" else "Edit record",
        onBack = back,
    ) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = ProofLedgerDimens.screenHorizontal,
                    vertical = ProofLedgerDimens.screenVertical,
                ),
            verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap),
        ) {
            // Phase 8 banner stack. Three mutually-exclusive states:
            //   1. ocrRunning && !ocrDraftApplied   → "Drafting from your screenshot…"
            //   2. serverDraftError != null         → "Couldn't read this one — type the fields yourself."
            //   3. ocrDraftApplied                  → "Draft ready — please review."
            // None of these block the form; the user can always type instead.
            when {
                state.ocrRunning && !state.ocrDraftApplied -> {
                    FormNoticePanel(accentColor = ProofLime) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Drafting from your screenshot…",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = NeutralInk,
                            )
                            Text(
                                "We're reading the proof so we can pre-fill the form. You can keep typing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = NeutralMuted,
                            )
                        }
                    }
                }
                state.serverDraftError != null -> {
                    FormNoticePanel(accentColor = ProofAmber) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Draft unavailable",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = NeutralInk,
                            )
                            Text(
                                state.serverDraftError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = NeutralMuted,
                            )
                            QuietButton(
                                text = "Dismiss",
                                onClick = viewModel::onServerDraftErrorDismissed,
                            )
                        }
                    }
                }
                state.ocrDraftApplied -> {
                    FormNoticePanel(accentColor = ProofLime) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Draft ready",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = NeutralInk,
                            )
                            Text(
                                "We filled in what we could read. Please review before saving.",
                                style = MaterialTheme.typography.bodySmall,
                                color = NeutralMuted,
                            )
                        }
                    }
                }
            }
            SectionLabel("Record")
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
            SectionLabel("Who and category")
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
                            onClick = {
                                focusManager.clearFocus()
                                selectCounterparty(counterparty)
                            },
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
            // Category picker: tap a chip for a seeded/known category, or type
            // a new one and tap "Add". The chosen name is stored on the
            // transaction as a plain string; the chips just stop the field
            // from being an unhelpful blank box and keep report grouping tidy.
            ProofLedgerTextField(
                value = state.category,
                onValueChange = viewModel::onCategoryChange,
                label = "Category",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Tap a category, or type your own and tap Add.",
                style = MaterialTheme.typography.bodySmall,
                color = NeutralMuted,
            )
            val normalizedCategory = state.category.trim().lowercase().replace(Regex("\\s+"), " ")
            if (state.categories.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.categories.forEach { category ->
                        ProofLedgerFilterChip(
                            selected = category.normalizedName == normalizedCategory,
                            onClick = {
                                focusManager.clearFocus()
                                selectCategory(category.name)
                            },
                            label = category.name,
                        )
                    }
                }
            }
            val categoryExactMatch = state.categories.any { it.normalizedName == normalizedCategory }
            if (normalizedCategory.isNotBlank() && !categoryExactMatch) {
                AssistChip(
                    onClick = { addCategory(state.category) },
                    label = { Text("Add \"${state.category.trim()}\"") },
                )
            }

            // Phase 13 Cut 3: link money-in to a house (the buyer's unit) and
            // optionally earmark it to a specific installment.
            if (state.direction == "money_in" && state.linkableUnits.isNotEmpty()) {
                Text(
                    "Link to a house (optional)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeutralMuted,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProofLedgerFilterChip(
                        selected = state.selectedUnitId == null,
                        onClick = { viewModel.onSelectUnit(null) },
                        label = "No house",
                    )
                    state.linkableUnits.forEach { unit ->
                        ProofLedgerFilterChip(
                            selected = state.selectedUnitId == unit.id,
                            onClick = { viewModel.onSelectUnit(unit.id) },
                            label = unit.label,
                        )
                    }
                }
                if (state.selectedUnitId != null && state.earmarkOptions.isNotEmpty()) {
                    Text(
                        "Earmark to an installment (optional)",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeutralMuted,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ProofLedgerFilterChip(
                            selected = state.selectedEarmarkInstallmentId == null,
                            onClick = { viewModel.onSelectEarmark(null) },
                            label = "Auto",
                        )
                        state.earmarkOptions.forEach { line ->
                            ProofLedgerFilterChip(
                                selected = state.selectedEarmarkInstallmentId == line.id,
                                onClick = { viewModel.onSelectEarmark(line.id) },
                                label = "#${line.sequence} · ${formatNaira(line.amountMinor)}",
                            )
                        }
                    }
                }
            }

            // Phase 13 Cut 5: tag money-out to a project (e.g. land, materials,
            // wages for a development). "No project" = the general bucket.
            if (state.direction == "money_out" && state.projectOptions.isNotEmpty()) {
                Text(
                    "Project (optional)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeutralMuted,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProofLedgerFilterChip(
                        selected = state.selectedProjectId == null,
                        onClick = { viewModel.onSelectProject(null) },
                        label = "No project",
                    )
                    state.projectOptions.forEach { project ->
                        ProofLedgerFilterChip(
                            selected = state.selectedProjectId == project.id,
                            onClick = { viewModel.onSelectProject(project.id) },
                            label = project.name,
                        )
                    }
                }
            }

            SectionLabel("Payment details")
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
            SectionLabel("Proof")
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
            if (state.duplicateCandidates.isNotEmpty()) {
                DuplicateWarningCard(
                    candidates = state.duplicateCandidates,
                    direction = state.direction,
                    onReview = viewModel::dismissDuplicateWarning,
                    onSaveAnyway = saveAnyway,
                )
            }
            Spacer(Modifier.height(8.dp))
            PrimaryActionButton(
                text = when {
                    state.loading -> "Saving..."
                    state.duplicateCheckRunning -> "Checking..."
                    else -> "Save record"
                },
                onClick = save,
                enabled = !state.loading && !state.duplicateCheckRunning,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Filled.Check,
            )
        }
    }
}

@Composable
private fun DuplicateWarningCard(
    candidates: List<DuplicateCandidate>,
    direction: String,
    onReview: () -> Unit,
    onSaveAnyway: () -> Unit,
) {
    FormNoticePanel(accentColor = ProofAmber, backgroundColor = SurfaceCard) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "This looks like a record you already saved.",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = NeutralInk,
            )
            candidates.take(3).forEach { candidate ->
                val row = candidate.transaction
                Text(
                    text = buildString {
                        append(formatNaira(row.amountMinor))
                        append(" - ")
                        append(displayDate(row.transactionDate))
                        append(" - ")
                        append(row.counterpartyName ?: if (direction == "money_in") "Who paid not set" else "Who you paid not set")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = NeutralInk,
                )
                Text(
                    text = candidate.reasons.joinToString(", ") { it.label },
                    style = MaterialTheme.typography.bodySmall,
                    color = NeutralMuted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton(
                    text = "Review fields",
                    onClick = onReview,
                    modifier = Modifier.weight(1f),
                )
                PrimaryActionButton(
                    text = "Save anyway",
                    onClick = onSaveAnyway,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Check,
                )
            }
        }
    }
}

@Composable
private fun FormNoticePanel(
    accentColor: Color,
    modifier: Modifier = Modifier,
    backgroundColor: Color = SurfacePanel,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(ProofLedgerDimens.panelRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, ProofBorder, shape),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(accentColor),
        )
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            content = content,
        )
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
