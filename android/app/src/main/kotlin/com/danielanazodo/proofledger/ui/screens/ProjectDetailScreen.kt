package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
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
import com.danielanazodo.proofledger.ui.components.InfoCallout
import com.danielanazodo.proofledger.ui.components.InitialsBox
import com.danielanazodo.proofledger.ui.components.KeyFigure
import com.danielanazodo.proofledger.ui.components.LeadingIconBox
import com.danielanazodo.proofledger.ui.components.MoneyAmount
import com.danielanazodo.proofledger.ui.components.PanelEmptyState
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerConfirmDialog
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.components.ProportionBar
import com.danielanazodo.proofledger.ui.components.SectionHeader
import com.danielanazodo.proofledger.ui.components.TagBadge
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.theme.MoneyOutRed
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofIcons
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.theme.ProofLedgerDimens
import com.danielanazodo.proofledger.ui.theme.SurfaceCard
import com.danielanazodo.proofledger.ui.theme.proofLedgerScreenPadding
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.ProjectDetailViewModel

// Show search once the house list is taller than the scroll window, and cap the
// window so the list scrolls inside the panel instead of growing the page.
// 4, not 5: the redesigned rows (icon box + buyer badge, ~65dp) fit four in the
// 320dp window — at five the last row clips with no search or scroll hint.
private const val UNIT_FEED_VISIBLE_ROWS = 4
private val UNIT_FEED_MAX_HEIGHT = 320.dp

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
            verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.sectionGap),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap)) {
                // Teach only while there's nothing to show: once houses exist the
                // explainer would just be a permanent banner pushing them down.
                if (state.unitsTotal == 0) {
                    InfoCallout(
                        "Each house (unit) can have a buyer and a payment schedule. " +
                            "A standalone house is just a project with one unit.",
                    )
                }
                GroupedPanel {
                    Column(
                        modifier = Modifier.padding(ProofLedgerDimens.panelPadding),
                        verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap),
                    ) {
                        Text(
                            "Add a house",
                            color = NeutralInk,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        ProofLedgerTextField(
                            value = state.newUnitLabel,
                            onValueChange = viewModel::onNewUnitLabelChange,
                            label = "House / unit name (e.g. Plot 12)",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PrimaryActionButton(text = "Add house", onClick = addUnit, icon = Icons.Filled.Add)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap)) {
                SectionHeader(title = "Houses", count = state.unitsTotal.takeIf { it > 0 })
                if (state.unitsTotal == 0) {
                    PanelEmptyState(
                        title = "No houses yet",
                        message = "Add the first house above. Each one gets its own schedule.",
                        icon = ProofIcons.Home,
                    )
                } else {
                    // Search is for when the user remembers the house; it only earns its
                    // space once there are more houses than fit the scroll window.
                    if (state.unitsTotal > UNIT_FEED_VISIBLE_ROWS || state.unitQuery.isNotBlank()) {
                        ProofLedgerTextField(
                            value = state.unitQuery,
                            onValueChange = viewModel::onUnitQueryChange,
                            label = "Search houses",
                            leadingIcon = ProofIcons.Search,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (state.units.isEmpty()) {
                        PanelEmptyState(
                            title = "No matching houses",
                            message = "No houses match \"${state.unitQuery.trim()}\".",
                            icon = ProofIcons.Search,
                        )
                    } else {
                        // Self-contained scroll window: flick through every house here
                        // without the whole page growing. Bounded height → the list
                        // scrolls inside the panel; short lists just wrap to content.
                        GroupedPanel {
                            LazyColumn(modifier = Modifier.heightIn(max = UNIT_FEED_MAX_HEIGHT)) {
                                itemsIndexed(state.units, key = { _, unit -> unit.id }) { index, unit ->
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
                        if (state.unitQuery.isBlank() && state.unitsTotal > UNIT_FEED_VISIBLE_ROWS) {
                            Text(
                                "Scroll to see all ${state.unitsTotal} houses.",
                                color = NeutralMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            // Phase 13 Cut 5: actual money-out tagged to this project, by category.
            Column(verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap)) {
                SectionHeader(title = "Spending on this project")
                val spend = state.spend
                if (spend.totalMinor == 0L) {
                    PanelEmptyState(
                        title = "No spending yet",
                        message = "Tag money out to this project when you record it.",
                        icon = ProofIcons.Tag,
                    )
                } else {
                    GroupedPanel {
                        KeyFigure(
                            label = "Total spent",
                            amountText = formatNaira(spend.totalMinor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(ProofLedgerDimens.panelPadding),
                        )
                        if (spend.byCategory.isNotEmpty()) {
                            HorizontalDivider()
                            Column(
                                modifier = Modifier.padding(
                                    horizontal = ProofLedgerDimens.panelPadding,
                                    vertical = 14.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                spend.byCategory.forEach { line ->
                                    SpendCategoryLine(
                                        category = line.category,
                                        amountMinor = line.totalMinor,
                                        totalMinor = spend.totalMinor,
                                    )
                                }
                            }
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
            onRemove = {
                viewModel.onRemoveBuyer(unit.id)
                assigningBuyerFor = null
            },
            onDismiss = { assigningBuyerFor = null },
        )
    }
}

/**
 * One category's share of the project's spend: ink label, right-aligned mono
 * amount, and a thin bar showing its share of the total already on screen.
 */
@Composable
private fun SpendCategoryLine(
    category: String,
    amountMinor: Long,
    totalMinor: Long,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                category,
                color = NeutralInk,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            MoneyAmount(
                text = formatNaira(amountMinor),
                color = NeutralInk,
                preferredSize = MaterialTheme.typography.titleSmall.fontSize,
                minimumSize = MaterialTheme.typography.labelSmall.fontSize,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(130.dp),
            )
        }
        ProportionBar(fraction = if (totalMinor > 0) amountMinor.toFloat() / totalMinor else 0f)
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
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeadingIconBox(icon = ProofIcons.Home)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = NeutralInk,
                fontWeight = FontWeight.Medium,
            )
            if (buyerName != null) {
                TagBadge(text = buyerName, emphasized = true, icon = Icons.Filled.Person)
            } else {
                TagBadge(text = "No buyer yet")
            }
        }
        // Unassigned units surface the "add" affordance in ink so the open loop
        // is visible at a glance; once assigned, the action recedes to muted.
        IconButton(onClick = assign) {
            Icon(
                imageVector = if (buyerName == null) Icons.Filled.PersonAdd else Icons.Filled.Person,
                contentDescription = if (buyerName == null) {
                    "Assign buyer to $label"
                } else {
                    "Change buyer for $label"
                },
                tint = if (buyerName == null) NeutralInk else NeutralMuted,
            )
        }
        IconButton(onClick = delete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Remove $label",
                tint = NeutralMuted,
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = "Open schedule for $label",
            tint = NeutralMuted,
        )
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
    onRemove: () -> Unit,
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
                    if (selectedBuyerId != null) "Change buyer" else "Assign a buyer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NeutralInk,
                )
                ProofLedgerTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Search people",
                    leadingIcon = ProofIcons.Search,
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (selectedBuyerId != null) {
                        Arrangement.SpaceBetween
                    } else {
                        Arrangement.End
                    },
                ) {
                    // Removing a buyer is reversible (re-assign anytime), so no
                    // confirm step — but it's a real change, hence the red label.
                    if (selectedBuyerId != null) {
                        TextButton(onClick = onRemove) {
                            Text("Remove buyer", color = MoneyOutRed, fontWeight = FontWeight.Medium)
                        }
                    }
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InitialsBox(name = name)
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = NeutralInk, fontWeight = FontWeight.Medium)
            Text(typeLabel, color = NeutralMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = ProofLime)
        }
    }
}
