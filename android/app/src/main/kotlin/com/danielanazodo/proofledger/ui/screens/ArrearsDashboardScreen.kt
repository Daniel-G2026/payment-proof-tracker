package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.data.balance.BuyerArrears
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.KeyFigure
import com.danielanazodo.proofledger.ui.components.PanelEmptyState
import com.danielanazodo.proofledger.ui.components.SectionHeader
import com.danielanazodo.proofledger.ui.components.StatusDot
import com.danielanazodo.proofledger.ui.components.TagBadge
import com.danielanazodo.proofledger.ui.format.formatDueDate
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.format.relativeDueLabel
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofAmber
import com.danielanazodo.proofledger.ui.theme.ProofAmberDeep
import com.danielanazodo.proofledger.ui.theme.ProofIcons
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.theme.ProofLedgerDimens
import com.danielanazodo.proofledger.ui.theme.proofLedgerScreenPadding
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.ArrearsDashboardViewModel
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun ArrearsDashboardScreen(
    onBack: () -> Unit,
    onPersonClick: (String) -> Unit,
    viewModel: ArrearsDashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dash = state.dashboard
    val today = remember { LocalDate.now() }

    ProofLedgerScreen(title = "Who's due this month", onBack = onBack) { modifier ->
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).proofLedgerScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap),
        ) {
            if (dash == null) {
                PanelEmptyState(
                    title = "No schedules yet",
                    message = "Add a project, a house, and an offer-letter schedule " +
                        "to track who's due.",
                    icon = ProofIcons.Projects,
                )
                return@Column
            }

            val monthLabel = "${
                dash.month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
            } ${dash.month.year}"
            Text(monthLabel, color = NeutralMuted, style = MaterialTheme.typography.bodySmall)
            // Same composition as the Projects-tab hero card, so the figure the
            // user tapped through on keeps its shape here.
            GroupedPanel {
                Column(
                    Modifier.fillMaxWidth().padding(ProofLedgerDimens.panelPadding),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    KeyFigure(
                        label = "Outstanding",
                        amountText = formatNaira(dash.combinedOutstandingMinor),
                        color = if (dash.combinedOutstandingMinor > 0) ProofAmberDeep else NeutralInk,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        StatusDot(color = ProofAmber)
                        Text(
                            "${dash.peopleDueThisMonth} due",
                            color = NeutralMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        StatusDot(color = ProofLime)
                        Text(
                            "${dash.peopleFulfilledThisMonth} fulfilled",
                            color = NeutralMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Buyers with something to show: due this month or carrying arrears.
            val relevant = dash.buyers.filter { it.dueThisMonth || it.combinedMinor > 0 }
            SectionHeader(title = "People", count = relevant.size.takeIf { it > 0 })
            if (relevant.isEmpty()) {
                PanelEmptyState(
                    title = "All caught up",
                    message = "Everyone is up to date this month.",
                    icon = ProofIcons.People,
                )
            } else {
                GroupedPanel {
                    relevant.forEachIndexed { index, buyer ->
                        BuyerRow(buyer, today, onClick = { onPersonClick(buyer.buyerId) })
                        if (index != relevant.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun BuyerRow(buyer: BuyerArrears, today: LocalDate, onClick: () -> Unit) {
    val multiUnit = buyer.units.size > 1
    val open = rememberDebouncedClick(onClick = onClick)
    Column(
        Modifier.fillMaxWidth().clickable(onClick = open).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(buyer.buyerName, color = NeutralInk, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (buyer.fulfilledThisMonth && buyer.behindMinor == 0L) {
                    TagBadge(text = "Fulfilled", emphasized = true)
                }
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = "Open ${buyer.buyerName}'s profile",
                    tint = NeutralMuted,
                )
            }
        }
        Text(
            "Behind: ${formatNaira(buyer.behindMinor)}  ·  Due this month: ${formatNaira(buyer.dueThisMonthMinor)}",
            color = if (buyer.combinedMinor > 0) ProofAmberDeep else NeutralMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        // Each thing they owe: amount + due date + a countdown. Unit label is
        // shown only when the buyer holds more than one unit (counted-once rule).
        buyer.units.forEach { unit ->
            unit.dueItems.forEach { item ->
                val prefix = if (multiUnit) "${unit.unitLabel} · " else ""
                Text(
                    "$prefix${formatNaira(item.shortfallMinor)} · due ${formatDueDate(item.dueDate)} · " +
                        relativeDueLabel(item.dueDate, today),
                    color = if (item.pastDue) ProofAmberDeep else NeutralMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
