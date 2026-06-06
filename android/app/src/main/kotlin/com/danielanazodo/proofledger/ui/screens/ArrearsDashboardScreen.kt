package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.format.formatDueDate
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.format.relativeDueLabel
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofAmber
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.viewmodel.ArrearsDashboardViewModel
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun ArrearsDashboardScreen(
    onBack: () -> Unit,
    viewModel: ArrearsDashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dash = state.dashboard
    val today = remember { LocalDate.now() }

    ProofLedgerScreen(title = "Who's due this month", onBack = onBack) { modifier ->
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (dash == null) {
                Text(
                    "No schedules yet. Add a project, a house, and an offer-letter " +
                        "schedule to track who's due.",
                    color = NeutralMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            val monthLabel = "${
                dash.month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
            } ${dash.month.year}"
            Text(monthLabel, color = NeutralMuted, style = MaterialTheme.typography.bodySmall)
            GroupedPanel {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${dash.peopleDueThisMonth} due · ${dash.peopleFulfilledThisMonth} fulfilled",
                        color = NeutralInk,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Outstanding  ${formatNaira(dash.combinedOutstandingMinor)}",
                        color = if (dash.combinedOutstandingMinor > 0) ProofAmber else ProofLime,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Buyers with something to show: due this month or carrying arrears.
            val relevant = dash.buyers.filter { it.dueThisMonth || it.combinedMinor > 0 }
            SectionLabel("People")
            if (relevant.isEmpty()) {
                Text(
                    "Everyone is up to date this month.",
                    color = NeutralMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                GroupedPanel {
                    relevant.forEachIndexed { index, buyer ->
                        BuyerRow(buyer, today)
                        if (index != relevant.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun BuyerRow(buyer: BuyerArrears, today: LocalDate) {
    val multiUnit = buyer.units.size > 1
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(buyer.buyerName, color = NeutralInk, fontWeight = FontWeight.Medium)
            if (buyer.fulfilledThisMonth && buyer.behindMinor == 0L) {
                Text("Fulfilled ✓", color = ProofLime, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            "Behind: ${formatNaira(buyer.behindMinor)}  ·  Due this month: ${formatNaira(buyer.dueThisMonthMinor)}",
            color = if (buyer.combinedMinor > 0) ProofAmber else NeutralMuted,
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
                    color = if (item.pastDue) ProofAmber else NeutralMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
