package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.danielanazodo.proofledger.data.local.ProofBackupStatus
import com.danielanazodo.proofledger.data.local.entity.LocalScreenshotEntity
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.ui.components.DetailLine
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.components.ProofStatusDot
import com.danielanazodo.proofledger.ui.components.QuietButton
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.components.SyncLabel
import com.danielanazodo.proofledger.ui.components.recordDirectionLabel
import com.danielanazodo.proofledger.ui.format.displayDate
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.theme.MoneyOutRed
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.SurfacePanel
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.TransactionDetailViewModel
import java.io.File

@Composable
fun TransactionDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: TransactionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val back = rememberDebouncedClick(onClick = onBack)
    val delete = rememberDebouncedClick(onClick = viewModel::delete)
    val backUpProof = rememberDebouncedClick(onClick = viewModel::backUpProofNow)
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    ProofLedgerScreen(title = "Record", onBack = back) { modifier ->
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val transaction = state.transaction
            if (transaction == null) {
                Text("Record not found.", color = NeutralMuted)
            } else {
                TransactionDetailContent(
                    transaction = transaction,
                    proof = state.proof,
                    onEdit = { onEdit(transaction.id) },
                    onDelete = delete,
                    onImportantChange = viewModel::setProofImportant,
                    onBackUpProof = backUpProof,
                )
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun TransactionDetailContent(
    transaction: LocalTransactionEntity,
    proof: LocalScreenshotEntity?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onImportantChange: (Boolean) -> Unit,
    onBackUpProof: () -> Unit,
) {
    val edit = rememberDebouncedClick(onClick = onEdit)
    val delete = rememberDebouncedClick(onClick = onDelete)
    var fullProofOpen by remember { mutableStateOf(false) }

    GroupedPanel {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(recordDirectionLabel(transaction.direction), color = NeutralMuted, style = MaterialTheme.typography.bodySmall)
                    Text(
                        formatNaira(transaction.amountMinor),
                        color = if (transaction.direction == "money_out") MoneyOutRed else NeutralInk,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                ProofStatusDot(if (proof == null) "missing proof" else "proof attached")
            }
            DetailLine("Date", displayDate(transaction.transactionDate))
            DetailLine(if (transaction.direction == "money_in") "Who paid" else "Who you paid", transaction.counterpartyName ?: "Not set")
            DetailLine("Category", transaction.category ?: "Not set")
            DetailLine("Bank or provider", transaction.bankName ?: "Not set")
            DetailLine("Reference", transaction.referenceNumber ?: "Not set")
            DetailLine("Note", transaction.note ?: "Not set")
            DetailLine("Sync", SyncLabel(transaction.syncStatus.name))
        }
    }

    SectionLabel("Proof")
    if (proof != null) {
        ProofCard(
            proof = proof,
            onOpen = { fullProofOpen = true },
            onImportantChange = onImportantChange,
            onBackUpProof = onBackUpProof,
        )
    } else {
        GroupedPanel {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("No proof attached", color = NeutralInk, style = MaterialTheme.typography.titleMedium)
                Text("Edit this record to add a screenshot or receipt.", color = NeutralMuted)
            }
        }
    }

    PrimaryActionButton("Edit record", onClick = edit, icon = Icons.Filled.Edit)
    QuietButton("Delete record", onClick = delete, contentColor = MoneyOutRed, icon = Icons.Filled.Delete)

    if (fullProofOpen && proof != null) {
        Dialog(onDismissRequest = { fullProofOpen = false }) {
            Card {
                AsyncImage(
                    model = File(proof.localOriginalPath),
                    contentDescription = "Full payment proof",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ProofCard(
    proof: LocalScreenshotEntity,
    onOpen: () -> Unit,
    onImportantChange: (Boolean) -> Unit,
    onBackUpProof: () -> Unit,
) {
    val open = rememberDebouncedClick(onClick = onOpen)
    val toggleImportant = rememberDebouncedClick { onImportantChange(!proof.isImportant) }
    val backUp = rememberDebouncedClick(onClick = onBackUpProof)
    GroupedPanel {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(
                model = File(proof.localOriginalPath),
                contentDescription = "Payment proof preview",
                modifier = Modifier.fillMaxWidth().height(190.dp).clickable(onClick = open),
            )
            DetailLine("Backup", proof.backupStatus.userLabel())
            proof.lastBackupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                QuietButton(
                    text = if (proof.isImportant) "Unmark important" else "Mark important",
                    onClick = toggleImportant,
                    modifier = Modifier.weight(1f),
                )
                QuietButton(
                    text = "Back up proof",
                    onClick = backUp,
                    enabled = proof.backupStatus != ProofBackupStatus.BACKED_UP &&
                        proof.backupStatus != ProofBackupStatus.BACKING_UP,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.CloudUpload,
                )
            }
        }
    }
}

private fun ProofBackupStatus.userLabel(): String = when (this) {
    ProofBackupStatus.SAVED_ON_PHONE_ONLY -> "Saved on this device"
    ProofBackupStatus.PENDING_BACKUP -> "Waiting to back up"
    ProofBackupStatus.WAITING_FOR_WIFI -> "Waiting for Wi-Fi"
    ProofBackupStatus.BACKING_UP -> "Backing up"
    ProofBackupStatus.BACKED_UP -> "Backed up"
    ProofBackupStatus.BACKUP_FAILED -> "Backup issue"
    ProofBackupStatus.PENDING_REMOTE_DELETE -> "Removing proof"
}
