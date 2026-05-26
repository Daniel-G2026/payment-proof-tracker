package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.MetricLine
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerMainScaffold
import com.danielanazodo.proofledger.ui.components.RecordListRow
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.components.StatusDot
import com.danielanazodo.proofledger.ui.components.SummaryPanel
import com.danielanazodo.proofledger.ui.components.VerticalSpacer
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.navigation.MainTab
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofGrayDot
import com.danielanazodo.proofledger.ui.theme.ProofAmber
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.theme.SurfaceCard
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
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
    onTabSelected: (MainTab) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val addMoneyIn = rememberDebouncedClick(onClick = onAddMoneyIn)
    val viewRecords = rememberDebouncedClick(onClick = onViewTransactions)
    val transactionClick = rememberDebouncedClick(onClick = onTransactionClick)
    val signOut = rememberDebouncedClick(onClick = viewModel::signOut)

    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }

    ProofLedgerMainScaffold(
        selectedTab = MainTab.Home,
        onTabSelected = onTabSelected,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.business?.name ?: "ProofLedger",
                                color = NeutralInk,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = NeutralMuted,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            StatusDot(color = if (state.syncing) ProofGrayDot else ProofLime)
                            Text(
                                if (state.syncing) "Syncing records" else "Saved on this device",
                                color = NeutralMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    TextButton(onClick = signOut) { Text("Sign out", color = NeutralMuted) }
                }
            }
        },
    ) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                SummaryPanel {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("This month", color = NeutralMuted, style = MaterialTheme.typography.bodySmall)
                        Text("Monthly", color = NeutralInk, style = MaterialTheme.typography.labelMedium)
                    }
                    MetricLine("In", formatNaira(state.monthMoneyInMinor), ProofLime)
                    MetricLine("Out", formatNaira(state.monthMoneyOutMinor), ProofGrayDot)
                    MetricLine("Net", formatNaira(state.monthMoneyInMinor - state.monthMoneyOutMinor), ProofGrayDot)
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(SurfaceCard.copy(alpha = 0.85f))
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${state.recordCount} records", color = NeutralInk, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusDot(color = ProofLime)
                            Text("${state.recordsWithProofCount} with proof", color = NeutralInk, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusDot(color = ProofAmber)
                            Text("${state.missingProofCount} missing", color = NeutralInk, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item { SectionLabel("Recent records") }
            if (state.recentTransactions.isEmpty()) {
                item {
                    GroupedPanel {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No records yet.", color = NeutralInk, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Add money in or money out, then attach proof when you have it.",
                                color = NeutralMuted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            } else {
                item {
                    GroupedPanel {
                        state.recentTransactions.forEachIndexed { index, transaction ->
                            RecordListRow(
                                transaction = transaction,
                                onClick = transactionClick,
                                showDivider = index != state.recentTransactions.lastIndex,
                            )
                        }
                    }
                }
            }
            item {
                TextButton(onClick = viewRecords) {
                    Text("View all records", color = NeutralMuted, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(8.dp))
                PrimaryActionButton(text = "Add record", onClick = addMoneyIn, icon = Icons.Filled.Add)
            }
            item { VerticalSpacer(12) }
        }
    }
}
