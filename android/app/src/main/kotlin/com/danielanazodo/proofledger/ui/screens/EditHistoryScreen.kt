package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.ui.components.EmptyState
import com.danielanazodo.proofledger.ui.components.ErrorState
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.LoadingState
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.components.VerticalSpacer
import com.danielanazodo.proofledger.ui.format.displayDate
import com.danielanazodo.proofledger.ui.history.EditHistoryEntry
import com.danielanazodo.proofledger.ui.history.FieldChange
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.viewmodel.EditHistoryViewModel

@Composable
fun EditHistoryScreen(
    onBack: () -> Unit,
    viewModel: EditHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ProofLedgerScreen(title = "Edit history", onBack = onBack) { contentModifier ->
        when {
            state.loading -> LoadingState(contentModifier)

            state.error != null -> ErrorState(
                message = state.error!!,
                modifier = contentModifier,
                onRetry = viewModel::load,
            )

            state.entries.isEmpty() -> EmptyState(
                message = "No changes recorded yet.",
                modifier = contentModifier,
            )

            else -> LazyColumn(
                modifier = contentModifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.entries, key = { it.id }) { entry -> HistoryCard(entry) }
            }
        }
    }
}

@Composable
private fun HistoryCard(entry: EditHistoryEntry) {
    GroupedPanel(modifier = Modifier.padding(0.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(entry.title, color = NeutralInk, fontWeight = FontWeight.SemiBold)
                Text(displayDate(entry.timestampIso.take(10)), color = NeutralMuted, fontSize = 12.sp)
            }
            if (entry.changes.isNotEmpty()) {
                VerticalSpacer(10)
                entry.changes.forEach { change ->
                    VerticalSpacer(6)
                    ChangeRow(change)
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(change: FieldChange) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(change.label)
        val text = when {
            change.oldValue == null -> change.newValue ?: "—"
            else -> "${change.oldValue ?: "—"}  →  ${change.newValue ?: "—"}"
        }
        Text(text, color = NeutralInk, fontSize = 14.sp)
    }
}
