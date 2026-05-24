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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.ui.viewmodel.TransactionListViewModel

@Composable
fun TransactionListScreen(
    onBack: () -> Unit,
    onTransactionClick: (String) -> Unit,
    viewModel: TransactionListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Transactions", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = state.search,
                onValueChange = viewModel::onSearchChange,
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.direction == null,
                    onClick = { viewModel.onDirectionChange(null) },
                    label = { Text("All") },
                )
                FilterChip(
                    selected = state.direction == "money_in",
                    onClick = { viewModel.onDirectionChange("money_in") },
                    label = { Text("Money in") },
                )
                FilterChip(
                    selected = state.direction == "money_out",
                    onClick = { viewModel.onDirectionChange("money_out") },
                    label = { Text("Money out") },
                )
            }
            if (state.transactions.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "No matching transactions.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(state.transactions, key = { it.id }) { transaction ->
                        TransactionRow(transaction = transaction, onClick = onTransactionClick)
                    }
                }
            }
        }
    }
}
