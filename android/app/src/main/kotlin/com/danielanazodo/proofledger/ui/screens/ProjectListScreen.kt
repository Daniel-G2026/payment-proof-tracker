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
import com.danielanazodo.proofledger.ui.components.PageHeader
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerConfirmDialog
import com.danielanazodo.proofledger.ui.components.ProofLedgerMainScaffold
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.navigation.MainTab
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofAmber
import com.danielanazodo.proofledger.ui.theme.ProofBorder
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
        topBar = { PageHeader(title = "Projects") },
    ) { modifier ->
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).proofLedgerScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap),
        ) {
            val arrears = state.arrears
            if (arrears != null) {
                ArrearsHeroCard(arrears = arrears, onClick = viewArrears)
            } else {
                ArrearsEntryRow(onClick = viewArrears)
            }
            Text(
                "A project is a development or estate — or a single house. " +
                    "Money in and money out can be tracked against it. Open one to add its houses.",
                style = MaterialTheme.typography.bodyMedium,
                color = NeutralMuted,
            )
            ProofLedgerTextField(
                value = state.newProject,
                onValueChange = viewModel::onNewProjectChange,
                label = "New project",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryActionButton(text = "Add project", onClick = addProject, icon = Icons.Filled.Add)

            SectionLabel("Your projects")
            if (state.total == 0) {
                Text(
                    "No projects yet. Add one above.",
                    color = NeutralMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // Search is for when the user remembers the project; it only earns
                // its space once there are more than fit the scroll window.
                if (state.total > PROJECT_FEED_VISIBLE_ROWS || state.query.isNotBlank()) {
                    ProofLedgerTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        label = "Search projects",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.projects.isEmpty()) {
                    Text(
                        "No projects match \"${state.query.trim()}\".",
                        color = NeutralMuted,
                        style = MaterialTheme.typography.bodyMedium,
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
 * Rich monthly arrears summary at the top of the Projects tab: due / fulfilled
 * counts and the combined outstanding balance, tappable to open the full
 * arrears dashboard. (Same summary the Home dashboard used to carry.)
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "This month · $monthLabel",
                color = NeutralMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${arrears.peopleDueThisMonth} due · ${arrears.peopleFulfilledThisMonth} fulfilled",
                color = NeutralInk,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Outstanding",
                        color = NeutralMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        formatNaira(arrears.combinedOutstandingMinor),
                        color = if (arrears.combinedOutstandingMinor > 0) ProofAmber else NeutralInk,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Details",
                        color = NeutralMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = "Open arrears dashboard",
                        tint = NeutralMuted,
                    )
                }
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
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                tint = ProofBorder,
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
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
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
            tint = ProofBorder,
        )
    }
}
