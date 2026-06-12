package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
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
import com.danielanazodo.proofledger.data.balance.ArrearsDashboard
import com.danielanazodo.proofledger.data.local.entity.LocalProjectEntity
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.InfoCallout
import com.danielanazodo.proofledger.ui.components.KeyFigure
import com.danielanazodo.proofledger.ui.components.LeadingIconBox
import com.danielanazodo.proofledger.ui.components.PageHeader
import com.danielanazodo.proofledger.ui.components.PanelEmptyState
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerConfirmDialog
import com.danielanazodo.proofledger.ui.components.ProofLedgerMainScaffold
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.components.SectionHeader
import com.danielanazodo.proofledger.ui.components.StatusDot
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.navigation.MainTab
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofAmber
import com.danielanazodo.proofledger.ui.theme.ProofAmberDeep
import com.danielanazodo.proofledger.ui.theme.ProofIcons
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.theme.ProofLedgerDimens
import com.danielanazodo.proofledger.ui.theme.proofLedgerScreenPadding
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.ProjectListViewModel

// Show search once the project list is taller than the scroll window, and cap the
// window so the list scrolls inside the panel instead of growing the page.
private const val PROJECT_FEED_VISIBLE_ROWS = 5
private val PROJECT_FEED_MAX_HEIGHT = 320.dp

@Composable
fun ProjectListScreen(
    onTabSelected: (MainTab) -> Unit,
    onProjectClick: (String) -> Unit,
    onViewArrears: () -> Unit,
    viewModel: ProjectListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val addProject = rememberDebouncedClick(onClick = viewModel::onAddProject)
    val viewArrears = rememberDebouncedClick(onClick = onViewArrears)
    var pendingDelete by remember { mutableStateOf<LocalProjectEntity?>(null) }

    ProofLedgerMainScaffold(
        selectedTab = MainTab.Projects,
        onTabSelected = onTabSelected,
        topBar = { PageHeader(title = "Projects", subtitle = "Houses, buyers, and spending per project") },
    ) { modifier ->
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).proofLedgerScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.sectionGap),
        ) {
            val arrears = state.arrears
            if (arrears != null) {
                ArrearsHeroCard(arrears = arrears, onClick = viewArrears)
            } else {
                ArrearsEntryRow(onClick = viewArrears)
            }

            Column(verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap)) {
                // Teach only while there's nothing to show: once projects exist the
                // explainer would just be a permanent banner pushing them down.
                if (state.total == 0) {
                    InfoCallout(
                        "A project is a development, an estate, or a single house. " +
                            "Track money in and out against it; open one to add its houses.",
                    )
                }
                GroupedPanel {
                    Column(
                        modifier = Modifier.padding(ProofLedgerDimens.panelPadding),
                        verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap),
                    ) {
                        Text(
                            "Add a project",
                            color = NeutralInk,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        ProofLedgerTextField(
                            value = state.newProject,
                            onValueChange = viewModel::onNewProjectChange,
                            label = "Project name",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PrimaryActionButton(text = "Add project", onClick = addProject, icon = Icons.Filled.Add)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap)) {
                SectionHeader(title = "Your projects", count = state.total.takeIf { it > 0 })
                if (state.total == 0) {
                    PanelEmptyState(
                        title = "No projects yet",
                        message = "Add your first project above to start tracking it.",
                        icon = ProofIcons.Projects,
                    )
                } else {
                    // Search is for when the user remembers the project; it only earns
                    // its space once there are more than fit the scroll window.
                    if (state.total > PROJECT_FEED_VISIBLE_ROWS || state.query.isNotBlank()) {
                        ProofLedgerTextField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            label = "Search projects",
                            leadingIcon = ProofIcons.Search,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (state.projects.isEmpty()) {
                        PanelEmptyState(
                            title = "No matching projects",
                            message = "No projects match \"${state.query.trim()}\".",
                            icon = ProofIcons.Search,
                        )
                    } else {
                        // Self-contained scroll window: flick through every project here
                        // without the whole page growing. Bounded height → the list
                        // scrolls inside the panel; short lists just wrap to content.
                        GroupedPanel {
                            LazyColumn(modifier = Modifier.heightIn(max = PROJECT_FEED_MAX_HEIGHT)) {
                                itemsIndexed(state.projects, key = { _, project -> project.id }) { index, project ->
                                    ProjectRow(
                                        name = project.name,
                                        onClick = { onProjectClick(project.id) },
                                        onDelete = { pendingDelete = project },
                                    )
                                    if (index != state.projects.lastIndex) {
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                        if (state.query.isBlank() && state.total > PROJECT_FEED_VISIBLE_ROWS) {
                            Text(
                                "Scroll to see all ${state.total} projects.",
                                color = NeutralMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { project ->
        ProofLedgerConfirmDialog(
            title = "Remove project?",
            message = "Remove \"${project.name}\"? Its houses will be removed too. " +
                "Records you already saved keep their details.",
            confirmText = "Remove",
            dismissText = "Keep",
            onConfirm = {
                viewModel.onDeleteProject(project.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * Rich monthly arrears summary at the top of the Projects tab: the combined
 * outstanding balance as the headline figure with due / fulfilled counts under
 * it, tappable to open the full arrears dashboard. (Same summary the Home
 * dashboard used to carry.)
 */
@Composable
private fun ArrearsHeroCard(arrears: ArrearsDashboard, onClick: () -> Unit) {
    val open = rememberDebouncedClick(onClick = onClick)
    val monthLabel = "${
        arrears.month.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    } ${arrears.month.year}"
    GroupedPanel {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = open)
                .padding(ProofLedgerDimens.panelPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "This month · $monthLabel",
                    color = NeutralMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Details",
                        color = NeutralInk,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = "Open arrears dashboard",
                        tint = NeutralInk,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            KeyFigure(
                label = "Outstanding",
                amountText = formatNaira(arrears.combinedOutstandingMinor),
                color = if (arrears.combinedOutstandingMinor > 0) ProofAmberDeep else NeutralInk,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                StatusDot(color = ProofAmber)
                Text(
                    "${arrears.peopleDueThisMonth} due",
                    color = NeutralMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                StatusDot(color = ProofLime)
                Text(
                    "${arrears.peopleFulfilledThisMonth} fulfilled",
                    color = NeutralMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ArrearsEntryRow(onClick: () -> Unit) {
    val open = rememberDebouncedClick(onClick = onClick)
    GroupedPanel {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = open)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LeadingIconBox(icon = ProofIcons.Reports)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "Who's due this month",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NeutralInk,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "See who has paid and who is behind across your projects.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeutralMuted,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Open arrears dashboard",
                tint = NeutralMuted,
            )
        }
    }
}

@Composable
private fun ProjectRow(
    name: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val open = rememberDebouncedClick(onClick = onClick)
    val delete = rememberDebouncedClick(onClick = onDelete)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = open)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeadingIconBox(icon = ProofIcons.Projects)
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = NeutralInk,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = delete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Remove $name",
                tint = NeutralMuted,
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = NeutralMuted,
        )
    }
}
