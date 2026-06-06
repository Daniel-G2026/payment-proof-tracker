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
import com.danielanazodo.proofledger.data.local.entity.LocalCategoryEntity
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerConfirmDialog
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofLedgerDimens
import com.danielanazodo.proofledger.ui.theme.proofLedgerScreenPadding
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.CategoryListViewModel

@Composable
fun CategoryListScreen(
    onBack: () -> Unit,
    viewModel: CategoryListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val addCategory = rememberDebouncedClick(onClick = viewModel::onAddCategory)
    var pendingDelete by remember { mutableStateOf<LocalCategoryEntity?>(null) }

    ProofLedgerScreen(
        title = "Categories",
        onBack = onBack,
    ) { modifier ->
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).proofLedgerScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap),
        ) {
            Text(
                "These label your money in and out. Add the ones your business uses; " +
                    "remove any you don't, including the ones we started you with.",
                style = MaterialTheme.typography.bodyMedium,
                color = NeutralMuted,
            )
            ProofLedgerTextField(
                value = state.newCategory,
                onValueChange = viewModel::onNewCategoryChange,
                label = "New category",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryActionButton(text = "Add category", onClick = addCategory, icon = Icons.Filled.Add)

            SectionLabel("Your categories")
            if (state.categories.isEmpty()) {
                Text(
                    "No categories yet. Add one above.",
                    color = NeutralMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                GroupedPanel {
                    state.categories.forEachIndexed { index, category ->
                        CategoryRow(
                            name = category.name,
                            onDelete = { pendingDelete = category },
                        )
                        if (index != state.categories.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { category ->
        ProofLedgerConfirmDialog(
            title = "Remove category?",
            message = "Remove \"${category.name}\" from your categories? Records you already saved with it keep their label. This only stops it being offered next time.",
            confirmText = "Remove",
            dismissText = "Keep",
            onConfirm = {
                viewModel.onDeleteCategory(category.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun CategoryRow(
    name: String,
    onDelete: () -> Unit,
) {
    val delete = rememberDebouncedClick(onClick = onDelete)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            color = NeutralInk,
            fontWeight = FontWeight.Medium,
        )
        IconButton(onClick = delete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Remove $name",
                tint = NeutralMuted,
            )
        }
    }
}
