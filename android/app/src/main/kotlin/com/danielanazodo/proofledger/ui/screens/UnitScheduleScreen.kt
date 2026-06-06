package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.data.balance.InstallmentFill
import com.danielanazodo.proofledger.data.balance.InstallmentState
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofAmber
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.UnitScheduleViewModel
import androidx.compose.ui.graphics.Color

@Composable
fun UnitScheduleScreen(
    onBack: () -> Unit,
    viewModel: UnitScheduleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val save = rememberDebouncedClick(onClick = viewModel::onSave)
    val isRenegotiation = state.activeVersion != null

    ProofLedgerScreen(
        title = "${state.unitLabel} schedule",
        onBack = onBack,
    ) { modifier ->
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
                    color = if (state.arrearsMinor > 0) ProofAmber else NeutralInk,
                )
            } else {
                Text(
                    "No schedule yet. Enter the offer-letter installments below — " +
                        "the amount due and the date it's due.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeutralMuted,
                )
            }

            SectionLabel(if (isRenegotiation) "Renegotiate (new version)" else "Enter schedule")
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
                    ProofLedgerTextField(
                        value = line.dueDate,
                        onValueChange = { viewModel.onDueDateChange(index, it) },
                        label = "Due (YYYY-MM-DD)",
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.onRemoveLine(index) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove line", tint = NeutralMuted)
                    }
                }
            }

            IconButton(onClick = viewModel::onAddLine) {
                Icon(Icons.Filled.Add, contentDescription = "Add installment", tint = NeutralInk)
            }

            state.error?.let { Text(it, color = ProofAmber, style = MaterialTheme.typography.bodySmall) }

            PrimaryActionButton(
                text = if (isRenegotiation) "Save as new version" else "Save schedule",
                onClick = save,
                enabled = !state.saving,
            )
        }
    }
}

private fun stateLabel(fill: InstallmentFill): String = when (fill.state) {
    InstallmentState.UNPAID -> "Unpaid"
    InstallmentState.PARTIAL -> "Part-paid · ${formatNaira(fill.filledMinor)} of ${formatNaira(fill.scheduledMinor)}"
    InstallmentState.FILLED -> "Paid"
    InstallmentState.FILLED_LATE -> "Paid (late)"
    InstallmentState.FILLED_AHEAD -> "Paid ahead"
}

private fun stateColor(state: InstallmentState): Color = when (state) {
    InstallmentState.UNPAID -> NeutralMuted
    InstallmentState.PARTIAL -> ProofAmber
    InstallmentState.FILLED -> ProofLime
    InstallmentState.FILLED_LATE -> ProofAmber
    InstallmentState.FILLED_AHEAD -> ProofLime
}
