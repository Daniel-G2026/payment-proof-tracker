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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.data.local.ProofBackupPreference
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.ProofBackupViewModel
import java.util.Locale

@Composable
fun ProofBackupScreen(
    onBack: () -> Unit,
    viewModel: ProofBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val back = rememberDebouncedClick(onClick = onBack)
    val backUpSelected = rememberDebouncedClick(onClick = viewModel::backUpSelected)
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TextButton(onClick = back) { Text("Back") }
            Text("Proof backup", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${state.pendingCount} proofs • about ${formatBytes(state.estimatedBytes)}",
                style = MaterialTheme.typography.titleMedium,
            )

            Text("Backup preference", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreferenceChip("Wi-Fi only", ProofBackupPreference.WIFI_ONLY, state.preference, viewModel::onPreferenceChange)
                PreferenceChip("Manual", ProofBackupPreference.MANUAL, state.preference, viewModel::onPreferenceChange)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreferenceChip("Important only", ProofBackupPreference.IMPORTANT_ONLY, state.preference, viewModel::onPreferenceChange)
            }

            Text("Manual backup filters", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.importantOnly,
                    onClick = { viewModel.onImportantOnlyChange(!state.importantOnly) },
                    label = { Text("Important") },
                )
                FilterChip(
                    selected = state.direction == "money_in",
                    onClick = { viewModel.onDirectionChange(if (state.direction == "money_in") null else "money_in") },
                    label = { Text("Money in") },
                )
                FilterChip(
                    selected = state.direction == "money_out",
                    onClick = { viewModel.onDirectionChange(if (state.direction == "money_out") null else "money_out") },
                    label = { Text("Money out") },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.minAmount,
                    onValueChange = viewModel::onMinAmountChange,
                    label = { Text("Min amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.maxAmount,
                    onValueChange = viewModel::onMaxAmountChange,
                    label = { Text("Max amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.startDate,
                    onValueChange = viewModel::onStartDateChange,
                    label = { Text("Start date") },
                    placeholder = { Text("YYYY-MM-DD") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.endDate,
                    onValueChange = viewModel::onEndDateChange,
                    label = { Text("End date") },
                    placeholder = { Text("YYYY-MM-DD") },
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = state.counterpartySearch,
                onValueChange = viewModel::onCounterpartySearchChange,
                label = { Text("Counterparty") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = backUpSelected,
                enabled = !state.loading && state.pendingCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.loading) "Backing up..." else "Back up selected proofs")
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
    FilterChip(
        selected = value == selected,
        onClick = { onSelect(value) },
        label = { Text(label) },
    )
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.toInt().coerceAtLeast(0)} KB"
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}
