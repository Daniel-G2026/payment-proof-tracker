package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.ProjectDetailViewModel

@Composable
fun ProjectDetailScreen(
    onBack: () -> Unit,
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
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
                            onAssignBuyer = { assigningBuyerFor = unit },
                            onDelete = { pendingDelete = unit },
                        )
                        if (index != state.units.lastIndex) {
                            HorizontalDivider()
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
            onDismiss = { assigningBuyerFor = null },
        )
    }
}

@Composable
private fun UnitRow(
    label: String,
    buyerName: String?,
    onAssignBuyer: () -> Unit,
    onDelete: () -> Unit,
) {
    val assign = rememberDebouncedClick(onClick = onAssignBuyer)
    val delete = rememberDebouncedClick(onClick = onDelete)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = assign)
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
                buyerName?.let { "Buyer: $it" } ?: "Tap to assign a buyer",
                style = MaterialTheme.typography.bodySmall,
                color = NeutralMuted,
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

@Composable
private fun BuyerPickerDialog(
    buyers: List<LocalCounterpartyEntity>,
    selectedBuyerId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign a buyer") },
        text = {
            if (buyers.isEmpty()) {
                Text(
                    "No people yet. Add a buyer under People first, then come back.",
                    color = NeutralMuted,
                )
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    buyers.forEach { buyer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = buyer.id == selectedBuyerId,
                                    onClick = { onSelect(buyer.id) },
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(
                                selected = buyer.id == selectedBuyerId,
                                onClick = { onSelect(buyer.id) },
                            )
                            Text(buyer.name, color = NeutralInk)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
