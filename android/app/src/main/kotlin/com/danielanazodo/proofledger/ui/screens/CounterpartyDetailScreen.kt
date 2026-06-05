package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
import com.danielanazodo.proofledger.ui.components.DetailLine
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.MetricLine
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerConfirmDialog
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.components.QuietButton
import com.danielanazodo.proofledger.ui.components.RecordListRow
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.components.SummaryPanel
import com.danielanazodo.proofledger.ui.components.SyncLabel
import com.danielanazodo.proofledger.ui.format.displayDate
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.theme.MoneyOutRed
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofGrayDot
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.CounterpartyDetailUiState
import com.danielanazodo.proofledger.ui.viewmodel.CounterpartyDetailViewModel

@Composable
fun CounterpartyDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onTransactionClick: (String) -> Unit,
    onViewHistory: (businessId: String, counterpartyId: String) -> Unit,
    viewModel: CounterpartyDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val back = rememberDebouncedClick(onClick = onBack)
    val transactionClick = rememberDebouncedClick(onClick = onTransactionClick)
    var confirmDeleteOpen by remember { mutableStateOf(false) }
    val delete = rememberDebouncedClick(onClick = { confirmDeleteOpen = true })
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    ProofLedgerScreen(title = "Person", onBack = back) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                val counterparty = state.counterparty
                if (counterparty == null) {
                    Text("Person not found.", color = NeutralMuted)
                } else {
                    PersonHeader(
                        counterparty = counterparty,
                        state = state,
                        onEdit = { onEdit(counterparty.id) },
                        onDelete = delete,
                        onViewHistory = { onViewHistory(counterparty.businessId, counterparty.id) },
                    )
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item { SectionLabel("Recent records") }
            if (state.transactions.isEmpty()) {
                item { Text("No records yet.", color = NeutralMuted) }
            } else {
                item {
                    GroupedPanel {
                        state.transactions.forEachIndexed { index, transaction ->
                            RecordListRow(
                                transaction = transaction,
                                onClick = transactionClick,
                                showDivider = index != state.transactions.lastIndex,
                            )
                        }
                    }
                }
            }
        }
    }

    val counterparty = state.counterparty
    if (confirmDeleteOpen && counterparty != null) {
        ProofLedgerConfirmDialog(
            title = "Delete person?",
            message = "This removes ${counterparty.name} from your people list. Saved records stay saved and still show the name they were created with.",
            confirmText = "Delete",
            dismissText = "Keep",
            onConfirm = {
                confirmDeleteOpen = false
                viewModel.delete()
            },
            onDismiss = { confirmDeleteOpen = false },
        )
    }
}

@Composable
private fun PersonHeader(
    counterparty: LocalCounterpartyEntity,
    state: CounterpartyDetailUiState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onViewHistory: () -> Unit,
) {
    val edit = rememberDebouncedClick(onClick = onEdit)
    val history = rememberDebouncedClick(onClick = onViewHistory)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupedPanel {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(counterparty.name, color = NeutralInk, style = MaterialTheme.typography.headlineSmall)
                DetailLine("Type", counterpartyTypeLabel(counterparty.type))
                DetailLine("Phone", counterparty.phone ?: "Not set")
                DetailLine("Notes", counterparty.notes ?: "Not set")
                DetailLine("Sync", SyncLabel(counterparty.syncStatus.name))
            }
        }
        SummaryCard(state)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryActionButton("Edit person", onClick = edit, modifier = Modifier.weight(1f), icon = Icons.Filled.Edit)
            QuietButton("Delete", onClick = onDelete, modifier = Modifier.weight(1f), contentColor = MoneyOutRed, icon = Icons.Filled.Delete)
        }
        QuietButton("View edit history", onClick = history, icon = Icons.Filled.History)
    }
}

@Composable
private fun SummaryCard(state: CounterpartyDetailUiState) {
    SummaryPanel {
        MetricLine("Money in", formatNaira(state.totalInMinor), ProofLime)
        MetricLine("Money out", formatNaira(state.totalOutMinor), ProofGrayDot)
        DetailLine("Records", state.transactionCount.toString())
        DetailLine("Last activity", state.lastTransactionDate?.let(::displayDate) ?: "Not set")
    }
}
