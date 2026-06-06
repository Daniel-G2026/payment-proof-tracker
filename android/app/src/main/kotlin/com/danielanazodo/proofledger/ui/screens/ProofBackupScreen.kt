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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.data.local.ProofBackupPreference
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.MetricLine
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerFilterChip
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.components.SummaryPanel
import com.danielanazodo.proofledger.ui.components.VerticalSpacer
import com.danielanazodo.proofledger.ui.format.formatCompactCount
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.ProofAmber
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.theme.ProofLedgerDimens
import com.danielanazodo.proofledger.ui.theme.proofLedgerScreenPadding
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.ProofBackupViewModel
import java.util.Locale

@Composable
fun ProofBackupScreen(
    onBack: () -> Unit,
    viewModel: ProofBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backUpSelected = rememberDebouncedClick(onClick = viewModel::backUpSelected)

    ProofLedgerScreen(
        title = "Proof backup",
        onBack = onBack,
    ) { modifier ->
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).proofLedgerScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap),
        ) {
            SummaryPanel {
                MetricLine("Waiting", "${formatCompactCount(state.pendingCount)} proofs", ProofAmber)
                MetricLine("Storage", formatBytes(state.estimatedBytes), ProofLime)
            }

            SectionLabel("Backup preference")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreferenceChip("Wi-Fi only", ProofBackupPreference.WIFI_ONLY, state.preference, viewModel::onPreferenceChange)
                PreferenceChip("Manual", ProofBackupPreference.MANUAL, state.preference, viewModel::onPreferenceChange)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreferenceChip("Important only", ProofBackupPreference.IMPORTANT_ONLY, state.preference, viewModel::onPreferenceChange)
            }

            SectionLabel("Manual filters")
            GroupedPanel {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProofLedgerFilterChip(
                            selected = state.importantOnly,
                            onClick = { viewModel.onImportantOnlyChange(!state.importantOnly) },
                            label = "Important",
                        )
                        ProofLedgerFilterChip(
                            selected = state.direction == "money_in",
                            onClick = { viewModel.onDirectionChange(if (state.direction == "money_in") null else "money_in") },
                            label = "Money in",
                        )
                        ProofLedgerFilterChip(
                            selected = state.direction == "money_out",
                            onClick = { viewModel.onDirectionChange(if (state.direction == "money_out") null else "money_out") },
                            label = "Money out",
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProofLedgerTextField(
                            value = state.minAmount,
                            onValueChange = viewModel::onMinAmountChange,
                            label = "Min amount",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        ProofLedgerTextField(
                            value = state.maxAmount,
                            onValueChange = viewModel::onMaxAmountChange,
                            label = "Max amount",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ProofLedgerTextField(
                        value = state.counterpartySearch,
                        onValueChange = viewModel::onCounterpartySearchChange,
                        label = "Person",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            PrimaryActionButton(
                text = if (state.loading) "Backing up..." else "Back up selected proofs",
                onClick = backUpSelected,
                enabled = !state.loading && state.pendingCount > 0,
                icon = Icons.Filled.CloudUpload,
            )
            state.message?.let { Text(it, color = NeutralInk) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            VerticalSpacer(12)
        }
    }
}

@Composable
private fun PreferenceChip(
    label: String,
    value: ProofBackupPreference,
    selected: ProofBackupPreference,
    onSelect: (ProofBackupPreference) -> Unit,
) {
    ProofLedgerFilterChip(
        selected = value == selected,
        onClick = { onSelect(value) },
        label = label,
    )
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.toInt().coerceAtLeast(0)} KB"
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}
