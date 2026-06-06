package com.danielanazodo.proofledger.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.data.sync.AUTH_EXPIRED_SYNC_MESSAGE
import com.danielanazodo.proofledger.data.sync.SyncIssue
import com.danielanazodo.proofledger.data.sync.SyncIssueEntityType
import com.danielanazodo.proofledger.data.sync.SyncIssueOpenTargetType
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.PageHeader
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.components.ProofStatusDot
import com.danielanazodo.proofledger.ui.components.QuietButton
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofLedgerDimens
import com.danielanazodo.proofledger.ui.theme.proofLedgerScreenPadding
import com.danielanazodo.proofledger.ui.viewmodel.SyncIssuesViewModel

@Composable
fun SyncIssuesScreen(
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onOpenPerson: (String) -> Unit,
    viewModel: SyncIssuesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    ProofLedgerScreen(
        title = "Sync issues",
        onBack = onBack,
        action = {
            TextButton(
                onClick = viewModel::retrySync,
                enabled = !state.loading && state.issues.any { it.canRetrySync },
            ) {
                Text("Try again")
            }
        },
    ) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxSize().proofLedgerScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProofLedgerDimens.itemGap),
        ) {
            item {
                Text(
                    text = "Items here are saved on this phone. We will keep them and try again when sync can continue.",
                    color = NeutralMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (state.issues.any { it.detail == AUTH_EXPIRED_SYNC_MESSAGE }) {
                item {
                    GroupedPanel {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Sign in again to sync", color = NeutralInk, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Your records and proofs are still saved on this phone.",
                                color = NeutralMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            item { SectionLabel("Needs attention") }
            if (state.issues.isEmpty()) {
                item {
                    Spacer(Modifier.height(18.dp))
                    Text("Nothing is stuck right now.", color = NeutralMuted)
                }
            } else {
                item {
                    GroupedPanel {
                        state.issues.forEachIndexed { index, issue ->
                            SyncIssueRow(
                                issue = issue,
                                showDivider = index != state.issues.lastIndex,
                                onRetryProof = { viewModel.retryProof(issue.entityId) },
                                onOpen = {
                                    when (issue.openTarget?.type) {
                                        SyncIssueOpenTargetType.RECORD -> onOpenRecord(issue.openTarget.id)
                                        SyncIssueOpenTargetType.PERSON -> onOpenPerson(issue.openTarget.id)
                                        null -> Unit
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncIssueRow(
    issue: SyncIssue,
    showDivider: Boolean,
    onRetryProof: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProofStatusDot(issue.statusLabel)
                Column {
                    Text(
                        issue.title,
                        color = NeutralInk,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(issue.entityType.label, color = NeutralMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(issue.statusLabel, color = NeutralMuted, style = MaterialTheme.typography.bodySmall)
        }
        issue.detail?.let {
            Text(it, color = NeutralMuted, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (issue.canRetryProof) {
                QuietButton(
                    text = "Try again",
                    onClick = onRetryProof,
                    icon = Icons.Filled.Refresh,
                    modifier = Modifier.weight(1f),
                )
            }
            if (issue.openTarget != null) {
                PrimaryActionButton(
                    text = "Open",
                    onClick = onOpen,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (showDivider) {
            Spacer(Modifier.height(1.dp))
        }
    }
}

private val SyncIssueEntityType.label: String
    get() = when (this) {
        SyncIssueEntityType.RECORD -> "Record"
        SyncIssueEntityType.PERSON -> "Person"
        SyncIssueEntityType.PROOF -> "Proof"
    }
