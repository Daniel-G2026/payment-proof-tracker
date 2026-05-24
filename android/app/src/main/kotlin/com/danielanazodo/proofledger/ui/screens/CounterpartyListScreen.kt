package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.CounterpartyListViewModel

@Composable
fun CounterpartyListScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onCounterpartyClick: (String) -> Unit,
    viewModel: CounterpartyListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val back = rememberDebouncedClick(onClick = onBack)
    val add = rememberDebouncedClick(onClick = onAdd)
    val counterpartyClick = rememberDebouncedClick(onClick = onCounterpartyClick)
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = back) { Text("Back") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Counterparties", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = add) { Text("Add") }
            }
            OutlinedTextField(
                value = state.search,
                onValueChange = viewModel::onSearchChange,
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            CounterpartyTypeChips(
                selectedType = state.type,
                onTypeChange = viewModel::onTypeChange,
                includeAll = true,
            )
            if (state.counterparties.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "No matching counterparties.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(state.counterparties, key = { it.id }) { counterparty ->
                        CounterpartyRow(counterparty = counterparty, onClick = counterpartyClick)
                    }
                }
            }
        }
    }
}

@Composable
fun CounterpartyRow(counterparty: LocalCounterpartyEntity, onClick: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable { onClick(counterparty.id) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(counterparty.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    counterpartyTypeLabel(counterparty.type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                counterparty.syncStatus.name.lowercase().replace("_", " "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun CounterpartyTypeChips(
    selectedType: String?,
    onTypeChange: (String?) -> Unit,
    includeAll: Boolean,
) {
    val types = listOf(
        "customer",
        "supplier",
        "subcontractor",
        "employee",
        "driver",
        "landlord",
        "agent",
        "other",
        "unknown",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (includeAll) {
                FilterChip(
                    selected = selectedType == null,
                    onClick = { onTypeChange(null) },
                    label = { Text("All") },
                )
            }
            types.take(4).forEach { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { onTypeChange(type) },
                    label = { Text(counterpartyTypeLabel(type)) },
                )
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            types.drop(4).forEach { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { onTypeChange(type) },
                    label = { Text(counterpartyTypeLabel(type)) },
                )
            }
        }
    }
}

fun counterpartyTypeLabel(type: String): String =
    type.split("_").joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
