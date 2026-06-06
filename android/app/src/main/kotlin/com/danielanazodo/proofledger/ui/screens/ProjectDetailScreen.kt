package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
import com.danielanazodo.proofledger.data.local.entity.LocalUnitEntity
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerConfirmDialog
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.theme.ProofLedgerDimens
import com.danielanazodo.proofledger.ui.theme.SurfaceCard
import com.danielanazodo.proofledger.ui.theme.proofLedgerScreenPadding
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.ProjectDetailViewModel

@Composable
fun ProjectDetailScreen(
    onBack: () -> Unit,
    onUnitSchedule: (String) -> Unit,
    viewModel: ProjectDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val addUnit = rememberDebouncedClick(onClick = viewModel::onAddUnit)
    var pendingDelete by remember { mutableStateOf<LocalUnitEntity?>(null) }
    var assigningBuyerFor by remember { mutableStateOf<LocalUnitEntity?>(null) }

    val buyerNamesById = remember(state.buyers) { state.buyers.associate { it.id to it.name } }

    ProofLedgerScreen(
        title = state.project?.name ?: "Project",
        onBack = onBack,
    ) { modifier ->
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).proofLedgerScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap),
        ) {
            Text(
                "Houses (units) in this project. Each can have a buyer. " +
                    "A standalone house is just a project with one unit.",
                style = MaterialTheme.typography.bodyMedium,
                color = NeutralMuted,
            )
            ProofLedgerTextField(
                value = state.newUnitLabel,
                onValueChange = viewModel::onNewUnitLabelChange,
                label = "New house / unit (e.g. Plot 12)",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryActionButton(text = "Add house", onClick = addUnit, icon = Icons.Filled.Add)

            SectionLabel("Houses")
            if (state.units.isEmpty()) {
                Text(
                    "No houses yet. Add one above.",
                    color = NeutralMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                GroupedPanel {
                    state.units.forEachIndexed { index, unit ->
                        UnitRow(
                            label = unit.label,
                            buyerName = unit.buyerCounterpartyId?.let { buyerNamesById[it] },
                            onOpenSchedule = { onUnitSchedule(unit.id) },
                            onAssignBuyer = { assigningBuyerFor = unit },
                            onDelete = { pendingDelete = unit },
                        )
                        if (index != state.units.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }

            // Phase 13 Cut 5: actual money-out tagged to this project, by category.
            SectionLabel("Spending on this project")
            val spend = state.spend
            if (spend.totalMinor == 0L) {
                Text(
                    "No money-out tagged to this project yet. Tag spending when you " +
                        "record money out.",
                    color = NeutralMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                GroupedPanel {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Total spent", color = NeutralInk, fontWeight = FontWeight.Medium)
                        Text(
                            formatNaira(spend.totalMinor),
                            color = NeutralInk,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    spend.byCategory.forEach { line ->
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(line.category, color = NeutralMuted)
                            Text(formatNaira(line.totalMinor), color = NeutralInk)
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { unit ->
        ProofLedgerConfirmDialog(
            title = "Remove house?",
            message = "Remove \"${unit.label}\" from this project?",
            confirmText = "Remove",
            dismissText = "Keep",
            onConfirm = {
                viewModel.onDeleteUnit(unit.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    assigningBuyerFor?.let { unit ->
        BuyerPickerDialog(
            buyers = state.buyers,
            selectedBuyerId = unit.buyerCounterpartyId,
            onSelect = { buyerId ->
                viewModel.onAssignBuyer(unit.id, buyerId)
                assigningBuyerFor = null
            },
            onCreate = { name ->
                viewModel.onCreateAndAssignBuyer(unit.id, name)
                assigningBuyerFor = null
            },
            onDismiss = { assigningBuyerFor = null },
        )
    }
}

@Composable
private fun UnitRow(
    label: String,
    buyerName: String?,
    onOpenSchedule: () -> Unit,
    onAssignBuyer: () -> Unit,
    onDelete: () -> Unit,
) {
    val openSchedule = rememberDebouncedClick(onClick = onOpenSchedule)
    val assign = rememberDebouncedClick(onClick = onAssignBuyer)
    val delete = rememberDebouncedClick(onClick = onDelete)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = openSchedule)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = NeutralInk,
                fontWeight = FontWeight.Medium,
            )
            Text(
                buyerName?.let { "Buyer: $it · tap for schedule" } ?: "Tap for schedule",
                style = MaterialTheme.typography.bodySmall,
                color = NeutralMuted,
            )
        }
        IconButton(onClick = assign) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = "Assign buyer to $label",
                tint = NeutralMuted,
            )
        }
        IconButton(onClick = delete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Remove $label",
                tint = NeutralMuted,
            )
        }
    }
}

/**
 * Searchable, filterable buyer picker styled in the app's design language (not a
 * default Material dialog). Shows a bounded set of matches with each person's
 * type as a subtitle so two same-named people are distinguishable, a type
 * filter, and an inline "add this person" when no match exists.
 */
@Composable
private fun BuyerPickerDialog(
    buyers: List<LocalCounterpartyEntity>,
    selectedBuyerId: String?,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<String?>(null) }
    val normalizedQuery = query.trim().lowercase()

    val matches = remember(buyers, query, typeFilter) {
        buyers.asSequence()
            .filter { typeFilter == null || it.type == typeFilter }
            .filter { normalizedQuery.isBlank() || it.name.lowercase().contains(normalizedQuery) }
            .take(MAX_BUYER_RESULTS)
            .toList()
    }
    val exactExists = buyers.any { it.name.trim().lowercase() == normalizedQuery }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(ProofLedgerDimens.panelRadius),
            color = SurfaceCard,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(ProofLedgerDimens.panelPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Assign a buyer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = NeutralInk,
                )
                ProofLedgerTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Search people",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                CounterpartyTypeChips(
                    selectedType = typeFilter,
                    onTypeChange = { typeFilter = it },
                    includeAll = true,
                )

                if (matches.isEmpty() && normalizedQuery.isBlank()) {
                    Text(
                        "No people yet. Type a name below to add one.",
                        color = NeutralMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    GroupedPanel {
                        matches.forEachIndexed { index, buyer ->
                            BuyerResultRow(
                                name = buyer.name,
                                typeLabel = counterpartyTypeLabel(buyer.type),
                                selected = buyer.id == selectedBuyerId,
                                onClick = { onSelect(buyer.id) },
                            )
                            if (index != matches.lastIndex) HorizontalDivider()
                        }
                    }
                }

                // Inline create when the typed name isn't already a person.
                if (normalizedQuery.isNotBlank() && !exactExists) {
                    TextButton(onClick = { onCreate(query.trim()) }) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = NeutralInk)
                        Text("  Add \"${query.trim()}\" as a new person", color = NeutralInk)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close", color = NeutralMuted) }
                }
            }
        }
    }
}

private const val MAX_BUYER_RESULTS = 6

@Composable
private fun BuyerResultRow(
    name: String,
    typeLabel: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val click = rememberDebouncedClick(onClick = onClick)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = click).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = NeutralInk, fontWeight = FontWeight.Medium)
            Text(typeLabel, color = NeutralMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = ProofLime)
        }
    }
}
