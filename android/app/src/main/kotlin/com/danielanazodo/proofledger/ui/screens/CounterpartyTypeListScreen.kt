package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerConfirmDialog
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.CounterpartyTypeListViewModel

@Composable
fun CounterpartyTypeListScreen(
    onBack: () -> Unit,
    viewModel: CounterpartyTypeListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val addType = rememberDebouncedClick(onClick = viewModel::onAddType)
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    ProofLedgerScreen(title = "Person types", onBack = onBack) { modifier ->
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "These are the kinds of people you track — who paid you, who you paid. " +
                    "Add the ones your business uses; remove any you don't. People you " +
                    "already saved keep their type.",
                style = MaterialTheme.typography.bodyMedium,
                color = NeutralMuted,
            )
            ProofLedgerTextField(
                value = state.newType,
                onValueChange = viewModel::onNewTypeChange,
                label = "New person type (e.g. Buyer)",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryActionButton(text = "Add type", onClick = addType, icon = Icons.Filled.Add)

            SectionLabel("Your person types")
            if (state.types.isEmpty()) {
                Text(
                    "No types yet. Add one above.",
                    color = NeutralMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                GroupedPanel {
                    state.types.forEachIndexed { index, type ->
                        TypeRow(
                            label = counterpartyTypeLabel(type),
                            onDelete = { pendingDelete = type },
                        )
                        if (index != state.types.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { type ->
        ProofLedgerConfirmDialog(
            title = "Remove person type?",
            message = "Remove \"${counterpartyTypeLabel(type)}\"? People you already saved with " +
                "it keep their type. This only stops it being offered next time.",
            confirmText = "Remove",
            dismissText = "Keep",
            onConfirm = {
                viewModel.onDeleteType(type)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun TypeRow(label: String, onDelete: () -> Unit) {
    val delete = rememberDebouncedClick(onClick = onDelete)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = NeutralInk, fontWeight = FontWeight.Medium)
        IconButton(onClick = delete) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove $label", tint = NeutralMuted)
        }
    }
}
