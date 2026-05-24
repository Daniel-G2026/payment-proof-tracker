package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.ui.format.displayDate
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.format.signedNaira
import com.danielanazodo.proofledger.ui.viewmodel.DashboardViewModel

@Composable
fun EmptyDashboardScreen(
    onSignedOut: () -> Unit,
    onAddMoneyIn: () -> Unit,
    onAddMoneyOut: () -> Unit,
    onViewTransactions: () -> Unit,
    onViewCounterparties: () -> Unit,
    onViewProofBackup: () -> Unit,
    onTransactionClick: (String) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }

    Scaffold { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                item {
                    Spacer(Modifier.height(32.dp))
                    Text(
                        text = state.business?.name ?: "ProofLedger",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    state.business?.businessType?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    TotalsCard(
                        title = "Today",
                        moneyIn = state.todayMoneyInMinor,
                        moneyOut = state.todayMoneyOutMinor,
                    )
                    Spacer(Modifier.height(12.dp))
                    TotalsCard(
                        title = "This month",
                        moneyIn = state.monthMoneyInMinor,
                        moneyOut = state.monthMoneyOutMinor,
                    )

                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onAddMoneyIn, modifier = Modifier.weight(1f)) {
                            Text("Add money in")
                        }
                        OutlinedButton(onClick = onAddMoneyOut, modifier = Modifier.weight(1f)) {
                            Text("Add money out")
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onViewCounterparties,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Counterparties")
                    }
                    OutlinedButton(
                        onClick = onViewProofBackup,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Proof backup")
                    }

                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onViewTransactions) { Text("View all") }
                    }
                }
                if (state.recentTransactions.isEmpty()) {
                    item {
                        Text(
                            "No money in or money out yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.recentTransactions, key = { it.id }) { transaction ->
                        TransactionRow(transaction = transaction, onClick = onTransactionClick)
                    }
                }
                item { Spacer(Modifier.height(88.dp)) }
            }

            TextButton(
                onClick = viewModel::signOut,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            ) { Text("Sign out") }
        }
    }
}

@Composable
private fun TotalsCard(title: String, moneyIn: Long, moneyOut: Long) {
    val net = moneyIn - moneyOut
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("Money in: ${formatNaira(moneyIn)}")
            Text("Money out: ${formatNaira(moneyOut)}")
            Text("Net: ${formatNaira(net)}", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
fun TransactionRow(transaction: LocalTransactionEntity, onClick: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable { onClick(transaction.id) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    transaction.counterpartyName
                        ?: if (transaction.direction == "money_in") "Money in" else "Money out",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    displayDate(transaction.transactionDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                signedNaira(transaction.amountMinor, transaction.direction),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}
