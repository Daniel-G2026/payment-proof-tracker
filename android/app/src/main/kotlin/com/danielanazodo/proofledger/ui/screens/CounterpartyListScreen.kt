package com.danielanazodo.proofledger.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.PageHeader
import com.danielanazodo.proofledger.ui.components.PersonListRow
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerFilterChip
import com.danielanazodo.proofledger.ui.components.ProofLedgerMainScaffold
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.navigation.MainTab
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofIcons
import com.danielanazodo.proofledger.ui.theme.ProofLedgerDimens
import com.danielanazodo.proofledger.ui.theme.proofLedgerScreenPadding
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.data.repository.CounterpartyTypeRepository
import com.danielanazodo.proofledger.ui.viewmodel.CounterpartyListViewModel

@Composable
fun CounterpartyListScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onCounterpartyClick: (String) -> Unit,
    onManageTypes: () -> Unit,
    onTabSelected: (MainTab) -> Unit,
    viewModel: CounterpartyListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val add = rememberDebouncedClick(onClick = onAdd)
    val manageTypes = rememberDebouncedClick(onClick = onManageTypes)
    val personClick = rememberDebouncedClick(onClick = onCounterpartyClick)

    ProofLedgerMainScaffold(
        selectedTab = MainTab.People,
        onTabSelected = onTabSelected,
        topBar = { PageHeader(title = "People", subtitle = "Customers, suppliers, and everyone you pay") },
    ) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxSize().proofLedgerScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap),
        ) {
            item { PrimaryActionButton(text = "Add person", onClick = add, icon = Icons.Filled.PersonAdd) }
            item {
                ProofLedgerTextField(
                    value = state.search,
                    onValueChange = viewModel::onSearchChange,
                    label = "Search people",
                    leadingIcon = ProofIcons.Search,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                CounterpartyTypeChips(
                    selectedType = state.type,
                    onTypeChange = viewModel::onTypeChange,
                    includeAll = true,
                    types = state.types,
                )
            }
            item {
                TextButton(onClick = manageTypes) {
                    Text("Manage person types", color = NeutralMuted)
                }
            }
            item { SectionLabel("Saved people") }
            if (state.counterparties.isEmpty()) {
                item {
                    Spacer(Modifier.height(18.dp))
                    Text("No matching people.", color = NeutralMuted, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                item {
                    GroupedPanel {
                        state.counterparties.forEachIndexed { index, person ->
                            PersonListRow(
                                counterparty = person,
                                onClick = personClick,
                                showDivider = index != state.counterparties.lastIndex,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CounterpartyTypeChips(
    selectedType: String?,
    onTypeChange: (String?) -> Unit,
    includeAll: Boolean,
    // The business's managed person types (Phase 13). Falls back to the built-in
    // defaults if the list hasn't loaded yet.
    types: List<String> = CounterpartyTypeRepository.BUILT_IN_TYPES,
) {
    val builtInTypes = types.ifEmpty { CounterpartyTypeRepository.BUILT_IN_TYPES }
    val customSelected = selectedType != null && selectedType !in builtInTypes
    var customOpen by remember { mutableStateOf(customSelected) }
    var customText by remember { mutableStateOf(if (customSelected) counterpartyTypeLabel(selectedType.orEmpty()) else "") }

    LaunchedEffect(selectedType) {
        if (selectedType != null && selectedType !in builtInTypes) {
            customOpen = true
            customText = counterpartyTypeLabel(selectedType)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (includeAll) {
                ProofLedgerFilterChip(
                    selected = selectedType == null,
                    onClick = { onTypeChange(null) },
                    label = "All",
                )
            }
            builtInTypes.forEach { type ->
                ProofLedgerFilterChip(
                    selected = selectedType == type,
                    onClick = { onTypeChange(type) },
                    label = counterpartyTypeLabel(type),
                )
            }
            if (includeAll && customSelected) {
                ProofLedgerFilterChip(
                    selected = true,
                    onClick = { onTypeChange(selectedType) },
                    label = counterpartyTypeLabel(selectedType.orEmpty()),
                )
            }
        }
        if (!includeAll) {
            TextButton(onClick = { customOpen = !customOpen }) {
                Text(if (customOpen) "Hide custom type" else "Create your own type")
            }
            if (customOpen) {
                ProofLedgerTextField(
                    value = customText,
                    onValueChange = { value ->
                        customText = value
                        normalizeCounterpartyType(value)?.let(onTypeChange)
                    },
                    label = "Custom person type",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

fun counterpartyTypeLabel(type: String): String =
    type.split("_").joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

private fun normalizeCounterpartyType(value: String): String? {
    val normalized = value
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
    return normalized.takeIf { it.isNotBlank() }
}
