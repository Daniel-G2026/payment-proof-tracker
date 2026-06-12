package com.danielanazodo.proofledger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.components.QuietButton
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.data.balance.InstallmentFill
import com.danielanazodo.proofledger.data.balance.InstallmentState
import com.danielanazodo.proofledger.data.repository.ScheduleDuplicateCandidate
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.format.todayIsoDate
import com.danielanazodo.proofledger.ui.theme.MoneyInGreen
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofAmberDeep
import com.danielanazodo.proofledger.ui.theme.ProofBorder
import com.danielanazodo.proofledger.ui.theme.ProofLedgerDimens
import com.danielanazodo.proofledger.ui.theme.proofLedgerScreenPadding
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.util.showAppDatePicker
import com.danielanazodo.proofledger.ui.viewmodel.UnitScheduleViewModel
import androidx.compose.ui.graphics.Color

@Composable
fun UnitScheduleScreen(
    onBack: () -> Unit,
    viewModel: UnitScheduleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val save = rememberDebouncedClick { viewModel.onSave() }
    val saveAnyway = rememberDebouncedClick(onClick = viewModel::onSaveAnyway)
    val revise = rememberDebouncedClick(onClick = viewModel::onRevise)
    val cancelEdit = rememberDebouncedClick(onClick = viewModel::onCancelEdit)
    val isRenegotiation = state.activeVersion != null
    // OpenDocument (not GetContent) so the picker can offer images AND PDFs —
    // offer letters are commonly shared as PDFs. We read the bytes immediately
    // during upload, so transient (non-persisted) read access is enough.
    val offerLetterPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onScanOfferLetter(uri.toString())
    }
    val scanOfferLetter = rememberDebouncedClick(windowMs = 1000L) {
        offerLetterPicker.launch(arrayOf("image/*", "application/pdf"))
    }
    val addBuyer = rememberDebouncedClick(onClick = viewModel::onAddOfferLetterBuyer)

    ProofLedgerScreen(
        title = "${state.unitLabel} schedule",
        onBack = onBack,
    ) { modifier ->
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).proofLedgerScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap),
        ) {
            // Post-save offer to add the offer-letter's buyer (Cut 6). Shown only
            // when the unit had no buyer and the AI named one. Opt-in; never
            // auto-assigns. Once added, a brief confirmation replaces it.
            state.addBuyerPromptName?.let { name ->
                GroupedPanel {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Schedule saved. The offer letter names \"$name\". " +
                                "Add them as this unit's buyer?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NeutralInk,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            QuietButton(
                                text = "Not now",
                                onClick = viewModel::onDismissAddBuyer,
                                modifier = Modifier.weight(1f),
                            )
                            PrimaryActionButton(
                                text = "Add as buyer",
                                onClick = addBuyer,
                                enabled = !state.addingBuyer,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            state.buyerAddedName?.let { name ->
                Text(
                    "$name is now this unit's buyer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MoneyInGreen,
                )
            }

            // Current active schedule + computed fill states, if any.
            if (state.activeVersion != null) {
                SectionLabel("Current schedule (v${state.activeVersion!!.versionNo})")
                GroupedPanel {
                    state.fills.forEachIndexed { index, fill ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("#${fill.sequence}  ${fill.dueDate}", color = NeutralInk)
                                Text(
                                    stateLabel(fill),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = stateColor(fill.state),
                                )
                            }
                            Text(
                                formatNaira(fill.scheduledMinor),
                                color = NeutralInk,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        if (index != state.fills.lastIndex) HorizontalDivider()
                    }
                }
                val total = state.fills.sumOf { it.scheduledMinor }
                Text(
                    "Total ${formatNaira(total)} · ${state.versionCount} version(s) on record",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeutralMuted,
                )
                Text(
                    buildString {
                        append("Behind today: ")
                        append(formatNaira(state.arrearsMinor))
                        if (state.creditMinor > 0) {
                            append("  ·  Credit: ")
                            append(formatNaira(state.creditMinor))
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (state.arrearsMinor > 0) ProofAmberDeep else NeutralInk,
                )
            } else if (!state.showEditor) {
                Text(
                    "No schedule yet. Add the offer-letter installments — the amount " +
                        "due and the date it's due.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeutralMuted,
                )
            }

            // Offer-letter scan (Cut 6): auto-fill the installments from a photo
            // or PDF of the letter. Available in both view and edit modes; the
            // editor opens pre-filled once the AI draft is ready.
            if (state.offerLetterScanning) {
                Text(
                    "Reading offer letter…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeutralMuted,
                )
            } else {
                QuietButton(
                    text = "Scan offer letter",
                    onClick = scanOfferLetter,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Pick a photo or PDF of the offer letter to fill the installments for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeutralMuted,
                )
            }
            state.offerLetterError?.let {
                Text(it, color = ProofAmberDeep, style = MaterialTheme.typography.bodySmall)
            }
            state.offerLetterBuyerName?.let { buyer ->
                if (state.offerLetterBuyerMismatch) {
                    Text(
                        "This letter names \"$buyer\", which doesn't match this unit's buyer. " +
                            "Make sure you're scanning the right unit.",
                        color = ProofAmberDeep,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "Offer letter buyer: $buyer",
                        color = NeutralMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (!state.showEditor) {
                // View mode: the schedule is read-only until the user revises it.
                PrimaryActionButton(
                    text = "Revise schedule",
                    onClick = revise,
                    icon = Icons.Filled.Edit,
                )
                Text(
                    "Revising creates a new version. Payments re-flow into it; older " +
                        "versions are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeutralMuted,
                )
            } else {
                SectionLabel(if (isRenegotiation) "Revise schedule (new version)" else "Enter schedule")
                state.draftLines.forEachIndexed { index, line ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProofLedgerTextField(
                            value = line.amount,
                            onValueChange = { viewModel.onAmountChange(index, it) },
                            label = "Amount (₦)",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        DateField(
                            value = line.dueDate,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                showAppDatePicker(
                                    context = context,
                                    currentIsoDate = line.dueDate.ifBlank { todayIsoDate() },
                                    onDateSelected = { viewModel.onDueDateChange(index, it) },
                                )
                            },
                        )
                        IconButton(onClick = { viewModel.onRemoveLine(index) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove line", tint = NeutralMuted)
                        }
                    }
                }

                TextButton(onClick = viewModel::onAddLine) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = NeutralInk)
                    Text("  Add installment", color = NeutralInk)
                }

                state.error?.let { Text(it, color = ProofAmberDeep, style = MaterialTheme.typography.bodySmall) }

                if (state.duplicateWarnings.isNotEmpty()) {
                    DuplicateWarningPanel(
                        candidates = state.duplicateWarnings,
                        saving = state.saving,
                        onReview = viewModel::onReviewDuplicates,
                        onSaveAnyway = saveAnyway,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (isRenegotiation) {
                            QuietButton(
                                text = "Cancel",
                                onClick = cancelEdit,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        PrimaryActionButton(
                            text = if (isRenegotiation) "Save as new version" else "Save schedule",
                            onClick = save,
                            enabled = !state.saving,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateWarningPanel(
    candidates: List<ScheduleDuplicateCandidate>,
    saving: Boolean,
    onReview: () -> Unit,
    onSaveAnyway: () -> Unit,
) {
    GroupedPanel {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "This looks like an offer letter you already entered.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = NeutralInk,
            )
            candidates.forEach { candidate ->
                Column {
                    Text(
                        listOfNotNull(candidate.buyerName, candidate.unitLabel.ifBlank { null })
                            .joinToString(" · ")
                            .ifBlank { "Existing schedule" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeutralInk,
                    )
                    Text(
                        "${candidate.installmentCount} installments · ${formatNaira(candidate.totalMinor)} · " +
                            candidate.reasons.joinToString(", ") { it.label },
                        style = MaterialTheme.typography.bodySmall,
                        color = NeutralMuted,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuietButton(
                    text = "Review fields",
                    onClick = onReview,
                    modifier = Modifier.weight(1f),
                )
                PrimaryActionButton(
                    text = "Save anyway",
                    onClick = onSaveAnyway,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DateField(
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val click = rememberDebouncedClick(onClick = onClick)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ProofLedgerDimens.panelRadius))
            .border(1.dp, ProofBorder, RoundedCornerShape(ProofLedgerDimens.panelRadius))
            .clickable(onClick = click)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.DateRange, contentDescription = null, tint = NeutralMuted)
        Text(
            value.ifBlank { "Due date" },
            color = if (value.isBlank()) NeutralMuted else NeutralInk,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun stateLabel(fill: InstallmentFill): String = when (fill.state) {
    InstallmentState.UNPAID -> "Unpaid"
    InstallmentState.PARTIAL -> "Part-paid · ${formatNaira(fill.filledMinor)} of ${formatNaira(fill.scheduledMinor)}"
    InstallmentState.FILLED -> "Paid"
    InstallmentState.FILLED_LATE -> "Paid (late)"
    InstallmentState.FILLED_AHEAD -> "Paid ahead"
}

// Status text needs readable colors: deep amber for caution, money-in green
// for paid. (Bright ProofAmber/ProofLime are dot/strip colors, ~2:1 as text.)
private fun stateColor(state: InstallmentState): Color = when (state) {
    InstallmentState.UNPAID -> NeutralMuted
    InstallmentState.PARTIAL -> ProofAmberDeep
    InstallmentState.FILLED -> MoneyInGreen
    InstallmentState.FILLED_LATE -> ProofAmberDeep
    InstallmentState.FILLED_AHEAD -> MoneyInGreen
}
