package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.ui.format.displayDate
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.viewmodel.TransactionDetailViewModel

@Composable
fun TransactionDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: TransactionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            val transaction = state.transaction
            if (transaction == null) {
                Text("Transaction not found.")
            } else {
                TransactionDetailContent(
                    transaction = transaction,
                    onEdit = { onEdit(transaction.id) },
                    onDelete = viewModel::delete,
                )
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun TransactionDetailContent(
    transaction: LocalTransactionEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Text(
        if (transaction.direction == "money_in") "Money in" else "Money out",
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(formatNaira(transaction.amountMinor), style = MaterialTheme.typography.headlineMedium)
    DetailLine("Date", displayDate(transaction.transactionDate))
    DetailLine(
        if (transaction.direction == "money_in") "Who paid" else "Who you paid",
        transaction.counterpartyName ?: "Not set",
    )
    DetailLine("Category", transaction.category ?: "Not set")
    DetailLine("Bank or provider", transaction.bankName ?: "Not set")
    DetailLine("Reference", transaction.referenceNumber ?: "Not set")
    DetailLine("Note", transaction.note ?: "Not set")
    DetailLine("Sync", transaction.syncStatus.name.lowercase().replace("_", " "))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Edit") }
        OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Delete") }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
