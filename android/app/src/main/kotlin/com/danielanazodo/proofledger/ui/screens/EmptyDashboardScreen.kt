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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.danielanazodo.proofledger.ui.format.formatCompactCount
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
    onManageCategories: () -> Unit,
    onViewProofBackup: () -> Unit,
    onViewReports: () -> Unit,
    onViewSyncIssues: () -> Unit,
    onTransactionClick: (String) -> Unit,
    onTabSelected: (MainTab) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableStateOf(DashboardRange.Monthly) }
    val addMoneyIn = rememberDebouncedClick(onClick = onAddMoneyIn)
    val viewRecords = rememberDebouncedClick(onClick = onViewTransactions)
    val viewReports = rememberDebouncedClick(onClick = onViewReports)
    val manageCategories = rememberDebouncedClick(onClick = onManageCategories)
    val viewSyncIssues = rememberDebouncedClick(onClick = onViewSyncIssues)
    val transactionClick = rememberDebouncedClick(onClick = onTransactionClick)
    val signOut = rememberDebouncedClick(onClick = viewModel::signOut)
    val downloadProofsNow = rememberDebouncedClick(onClick = viewModel::downloadProofsNow)
    val waitForWifiProofDownload =
        rememberDebouncedClick(onClick = viewModel::waitForWifiProofDownload)

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
                val moneyIn = when (range) {
                    DashboardRange.Daily -> state.todayMoneyInMinor
                    DashboardRange.Weekly -> state.weekMoneyInMinor
                    DashboardRange.Monthly -> state.monthMoneyInMinor
                }
                val moneyOut = when (range) {
                    DashboardRange.Daily -> state.todayMoneyOutMinor
                    DashboardRange.Weekly -> state.weekMoneyOutMinor
                    DashboardRange.Monthly -> state.monthMoneyOutMinor
                }
                SummaryPanel {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(range.headerLabel, color = NeutralMuted, style = MaterialTheme.typography.bodySmall)
                        Text(
                            range.toggleLabel,
                            color = NeutralInk,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { range = range.next() }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    MetricLine("In", formatNaira(moneyIn), ProofLime)
                    MetricLine("Out", formatNaira(moneyOut), ProofGrayDot)
                    MetricLine("Net", formatNaira(moneyIn - moneyOut), ProofGrayDot)
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(SurfaceCard.copy(alpha = 0.85f))
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusDot(color = if (state.missingProofCount > 0) ProofAmber else ProofLime)
                        Text(
                            text = proofStatusLine(
                                recordCount = state.recordCount,
                                recordsWithProofCount = state.recordsWithProofCount,
                                missingProofCount = state.missingProofCount,
                            ),
                            color = NeutralInk,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (state.syncIssueCount > 0) {
                item {
                    GroupedPanel {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Sync issue", color = NeutralInk, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${formatCompactCount(state.syncIssueCount)} item${if (state.syncIssueCount == 1) "" else "s"} need attention.",
                                    color = NeutralMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = viewSyncIssues) {
                                Text("Review", color = NeutralInk, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            if (state.pendingProofDownloadCount > 0) {
                item {
                    GroupedPanel {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Proof not on this phone",
                                color = NeutralInk,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${formatCompactCount(state.pendingProofDownloadCount)} proof" +
                                    "${if (state.pendingProofDownloadCount == 1) "" else "s"} " +
                                    "backed up to your account ${if (state.pendingProofDownloadCount == 1) "is" else "are"} " +
                                    "not on this phone yet.",
                                color = NeutralMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    onClick = downloadProofsNow,
                                    enabled = !state.downloadingProofs,
                                ) {
                                    Text(
                                        if (state.downloadingProofs) "Downloading…" else "Download now",
                                        color = NeutralInk,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                TextButton(
                                    onClick = waitForWifiProofDownload,
                                    enabled = !state.downloadingProofs,
                                ) {
                                    Text("Wait for Wi-Fi", color = NeutralMuted)
                                }
                            }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = viewRecords) {
                        Text("View all records", color = NeutralMuted, fontWeight = FontWeight.Medium)
                    }
                    TextButton(onClick = viewReports) {
                        Text("Reports & export", color = NeutralInk, fontWeight = FontWeight.Medium)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    TextButton(onClick = manageCategories) {
                        Text("Manage categories", color = NeutralMuted, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                PrimaryActionButton(text = "Add record", onClick = addMoneyIn, icon = Icons.Filled.Add)
            }
            item { VerticalSpacer(12) }
        }
    }
}

private enum class DashboardRange(val headerLabel: String, val toggleLabel: String) {
    Daily("Today", "Daily"),
    Weekly("This week", "Weekly"),
    Monthly("This month", "Monthly");

    fun next(): DashboardRange = when (this) {
        Daily -> Weekly
        Weekly -> Monthly
        Monthly -> Daily
    }
}

private fun proofStatusLine(
    recordCount: Int,
    recordsWithProofCount: Int,
    missingProofCount: Int,
): String {
    if (recordCount <= 0) return "No records yet"
    val records = "${formatCompactCount(recordCount)} records"
    return if (missingProofCount <= 0) {
        "$records - proofs attached"
    } else {
        "$records - ${formatCompactCount(recordsWithProofCount)} with proof - ${formatCompactCount(missingProofCount)} missing"
    }
}
