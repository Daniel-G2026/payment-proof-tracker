package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
import com.danielanazodo.proofledger.ui.format.displayDate
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.viewmodel.CounterpartyDetailUiState
import com.danielanazodo.proofledger.ui.viewmodel.CounterpartyDetailViewModel

@Composable
fun CounterpartyDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onTransactionClick: (String) -> Unit,
    viewModel: CounterpartyDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TextButton(onClick = onBack) { Text("Back") }
                val counterparty = state.counterparty
                if (counterparty == null) {
                    Text("Counterparty not found.")
                } else {
                    CounterpartyHeader(
                        counterparty = counterparty,
                        state = state,
                        onEdit = { onEdit(counterparty.id) },
                        onDelete = viewModel::delete,
                    )
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (state.transactions.isEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No transactions yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item { Text("Transactions", style = MaterialTheme.typography.titleMedium) }
                items(state.transactions, key = { it.id }) { transaction ->
                    TransactionRow(transaction = transaction, onClick = onTransactionClick)
                }
            }
        }
    }
}

@Composable
private fun CounterpartyHeader(
    counterparty: LocalCounterpartyEntity,
    state: CounterpartyDetailUiState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(counterparty.name, style = MaterialTheme.typography.headlineSmall)
        DetailLine("Type", counterpartyTypeLabel(counterparty.type))
        DetailLine("Phone", counterparty.phone ?: "Not set")
        DetailLine("Notes", counterparty.notes ?: "Not set")
        DetailLine("Sync", counterparty.syncStatus.name.lowercase().replace("_", " "))
        SummaryCard(state)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Edit") }
            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Delete") }
        }
    }
}

@Composable
private fun SummaryCard(state: CounterpartyDetailUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Summary", style = MaterialTheme.typography.titleMedium)
            Text("Money in: ${formatNaira(state.totalInMinor)}")
            Text("Money out: ${formatNaira(state.totalOutMinor)}")
            Text("Transactions: ${state.transactionCount}")
            Text("Last activity: ${state.lastTransactionDate?.let(::displayDate) ?: "Not set"}")
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
