package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.IconCircleButton
import com.danielanazodo.proofledger.ui.components.PageHeader
import com.danielanazodo.proofledger.ui.components.ProofLedgerFilterChip
import com.danielanazodo.proofledger.ui.components.ProofLedgerMainScaffold
import com.danielanazodo.proofledger.ui.components.OnboardingTipCard
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.components.RecordListRow
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.navigation.MainTab
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofIcons
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.theme.ProofLedgerDimens
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.TransactionListViewModel

@Composable
fun TransactionListScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onTransactionClick: (String) -> Unit,
    onManageCategories: () -> Unit,
    onTabSelected: (MainTab) -> Unit,
    viewModel: TransactionListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val transactionClick = rememberDebouncedClick(onClick = onTransactionClick)

    ProofLedgerMainScaffold(
        selectedTab = MainTab.Records,
        onTabSelected = onTabSelected,
        topBar = {
            PageHeader(
                title = "Records",
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconCircleButton(
                            icon = ProofIcons.Tag,
                            contentDescription = "Manage categories",
                            onClick = onManageCategories,
                            borderColor = ProofLime,
                        )
                        IconCircleButton(
                            icon = ProofIcons.Plus,
                            contentDescription = "Add record",
                            onClick = onAdd,
                            borderColor = ProofLime,
                        )
                    }
                },
            )
        },
    ) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(
                horizontal = ProofLedgerDimens.screenHorizontal,
                vertical = ProofLedgerDimens.screenVertical,
            ),
            verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap),
        ) {
            if (state.showCategoriesTip) {
                item {
                    OnboardingTipCard(
                        text = "Tip: organize your records with categories — tap the tag icon above to manage them.",
                        onDismiss = viewModel::dismissCategoriesTip,
                    )
                }
            }
            item {
                ProofLedgerTextField(
                    value = state.search,
                    onValueChange = viewModel::onSearchChange,
                    label = "Search records",
                    leadingIcon = ProofIcons.Search,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProofLedgerFilterChip(
                        selected = state.direction == null,
                        onClick = { viewModel.onDirectionChange(null) },
                        label = "All",
                    )
                    ProofLedgerFilterChip(
                        selected = state.direction == "money_in",
                        onClick = { viewModel.onDirectionChange("money_in") },
                        label = "Money in",
                    )
                    ProofLedgerFilterChip(
                        selected = state.direction == "money_out",
                        onClick = { viewModel.onDirectionChange("money_out") },
                        label = "Money out",
                    )
                    ProofLedgerFilterChip(
                        selected = state.missingProofOnly,
                        onClick = { viewModel.onMissingProofOnlyChange(!state.missingProofOnly) },
                        label = "Missing proof",
                    )
                }
            }
            item { SectionLabel("Saved records") }
            if (state.transactions.isEmpty()) {
                item {
                    Spacer(Modifier.height(18.dp))
                    Text("No matching records.", color = NeutralMuted, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                item {
                    GroupedPanel {
                        state.transactions.forEachIndexed { index, transaction ->
                            RecordListRow(
                                transaction = transaction,
                                onClick = transactionClick,
                                showDivider = index != state.transactions.lastIndex,
                            )
                        }
                    }
                }
            }
        }
    }
}
