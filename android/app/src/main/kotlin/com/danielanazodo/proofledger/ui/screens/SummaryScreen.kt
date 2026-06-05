package com.danielanazodo.proofledger.ui.screens

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.data.repository.NamedTotal
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.MetricLine
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.PageHeader
import com.danielanazodo.proofledger.ui.components.ProofLedgerFilterChip
import com.danielanazodo.proofledger.ui.components.ProofLedgerMainScaffold
import com.danielanazodo.proofledger.ui.components.QuietButton
import com.danielanazodo.proofledger.ui.navigation.MainTab
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.components.SummaryPanel
import com.danielanazodo.proofledger.ui.components.VerticalSpacer
import com.danielanazodo.proofledger.ui.format.PeriodUnit
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.format.relativeLabel
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofGrayDot
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.theme.SurfaceCard
import com.danielanazodo.proofledger.ui.viewmodel.SummaryViewModel

@Composable
fun SummaryScreen(
    onTabSelected: (MainTab) -> Unit,
    viewModel: SummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(state.shareUri) {
        state.shareUri?.let { uri ->
            val isPdf = state.shareMimeType == "application/pdf"
            val send = Intent(Intent.ACTION_SEND).apply {
                type = state.shareMimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, if (isPdf) "Share PDF" else "Share CSV"),
            )
            viewModel.onShareHandled()
        }
    }

    val summary = state.summary

    ProofLedgerMainScaffold(
        selectedTab = MainTab.Reports,
        onTabSelected = onTabSelected,
        topBar = { PageHeader(title = "Reports") },
    ) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProofLedgerFilterChip(
                        selected = state.unit == PeriodUnit.MONTH,
                        onClick = { viewModel.onUnitChange(PeriodUnit.MONTH) },
                        label = "Month",
                    )
                    ProofLedgerFilterChip(
                        selected = state.unit == PeriodUnit.WEEK,
                        onClick = { viewModel.onUnitChange(PeriodUnit.WEEK) },
                        label = "Week",
                    )
                }
            }

            item {
                PeriodSelector(
                    label = state.period.relativeLabel(),
                    canGoNext = state.canGoNext,
                    periods = state.availablePeriods.map { it to it.relativeLabel() },
                    onPrevious = viewModel::onPrevious,
                    onNext = viewModel::onNext,
                    onPick = { viewModel.onPickPeriod(it) },
                )
            }

            item {
                SummaryPanel {
                    MetricLine("Money in", formatNaira(summary?.moneyInMinor ?: 0), ProofLime)
                    MetricLine("Money out", formatNaira(summary?.moneyOutMinor ?: 0), ProofGrayDot)
                    MetricLine("Net", formatNaira(summary?.netMinor ?: 0), ProofGrayDot)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${summary?.recordCount ?: 0} record${if (summary?.recordCount == 1) "" else "s"} in this period",
                        color = NeutralMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (!summary?.topCounterparties.isNullOrEmpty()) {
                item { SectionLabel("Top people") }
                item { NamedTotalPanel(summary.topCounterparties) }
            }

            if (!summary?.topCategories.isNullOrEmpty()) {
                item { SectionLabel("Top categories") }
                item { NamedTotalPanel(summary.topCategories) }
            }

            item {
                val hasRecords = (summary?.recordCount ?: 0) > 0
                PrimaryActionButton(
                    text = if (state.exporting) "Preparing…" else "Export CSV",
                    onClick = viewModel::onExportCsv,
                    enabled = !state.exporting && hasRecords,
                    icon = Icons.Filled.Download,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Saves a spreadsheet of these records and opens the share sheet. Works offline.",
                    color = NeutralMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(16.dp))
                QuietButton(
                    text = if (state.exporting) "Preparing…" else "Export PDF",
                    onClick = viewModel::onExportPdf,
                    enabled = !state.exporting && hasRecords,
                    icon = Icons.Filled.PictureAsPdf,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Makes a printable PDF statement. Needs the internet.",
                    color = NeutralMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item { VerticalSpacer(12) }
        }
    }
}

@Composable
private fun PeriodSelector(
    label: String,
    canGoNext: Boolean,
    periods: List<Pair<com.danielanazodo.proofledger.ui.format.ReportPeriod, String>>,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPick: (com.danielanazodo.proofledger.ui.format.ReportPeriod) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous period", tint = NeutralInk)
        }
        Box {
            Text(
                text = label,
                color = NeutralInk,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                periods.forEach { (period, periodLabel) ->
                    DropdownMenuItem(
                        text = { Text(periodLabel) },
                        onClick = {
                            expanded = false
                            onPick(period)
                        },
                    )
                }
            }
        }
        IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "Next period",
                tint = if (canGoNext) NeutralInk else ProofGrayDot,
            )
        }
    }
}

@Composable
private fun NamedTotalPanel(items: List<NamedTotal>) {
    GroupedPanel {
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.name,
                    color = NeutralInk,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    formatNaira(item.totalMinor),
                    color = NeutralInk,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (index != items.lastIndex) {
                Spacer(Modifier.fillMaxWidth().height(1.dp).clip(RoundedCornerShape(1.dp)))
            }
        }
    }
}
