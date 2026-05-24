package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.danielanazodo.proofledger.ui.format.displayDate
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.viewmodel.TransactionDetailViewModel

@Composable
fun TransactionDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: TransactionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            val transaction = state.transaction
            if (transaction == null) {
                Text("Transaction not found.")
            } else {
                TransactionDetailContent(
                    transaction = transaction,
                    proof = state.proof,
                    onEdit = { onEdit(transaction.id) },
                    onDelete = viewModel::delete,
                    onImportantChange = viewModel::setProofImportant,
                    onBackUpProof = viewModel::backUpProofNow,
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
    var fullProofOpen by remember { mutableStateOf(false) }
    Text(
        if (transaction.direction == "money_in") "Money in" else "Money out",
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(formatNaira(transaction.amountMinor), style = MaterialTheme.typography.headlineMedium)
    DetailLine("Date", displayDate(transaction.transactionDate))
    DetailLine(
        if (transaction.direction == "money_in") "Who paid" else "Who you paid",
        transaction.counterpartyName ?: "Not set",
    )
    DetailLine("Category", transaction.category ?: "Not set")
    DetailLine("Bank or provider", transaction.bankName ?: "Not set")
    DetailLine("Reference", transaction.referenceNumber ?: "Not set")
    DetailLine("Note", transaction.note ?: "Not set")
    DetailLine("Sync", transaction.syncStatus.name.lowercase().replace("_", " "))
    if (proof != null) {
        ProofCard(
            proof = proof,
            onOpen = { fullProofOpen = true },
            onImportantChange = onImportantChange,
            onBackUpProof = onBackUpProof,
        )
    } else {
        DetailLine("Proof", "No screenshot attached")
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Edit") }
        OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Delete") }
    }
    if (fullProofOpen && proof != null) {
        Dialog(onDismissRequest = { fullProofOpen = false }) {
            Card {
                AsyncImage(
                    model = java.io.File(proof.localOriginalPath),
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Proof", style = MaterialTheme.typography.labelMedium)
            AsyncImage(
                model = java.io.File(proof.localOriginalPath),
                contentDescription = "Payment proof preview",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clickable(onClick = onOpen),
            )
            DetailLine("Backup", proof.backupStatus.userLabel())
            proof.lastBackupError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onImportantChange(!proof.isImportant) }) {
                    Text(if (proof.isImportant) "Unmark important" else "Mark important")
                }
                Button(
                    onClick = onBackUpProof,
                    enabled = proof.backupStatus != ProofBackupStatus.BACKED_UP &&
                        proof.backupStatus != ProofBackupStatus.BACKING_UP,
                ) {
                    Text("Back up proof")
                }
            }
        }
    }
}

private fun ProofBackupStatus.userLabel(): String = when (this) {
    ProofBackupStatus.SAVED_ON_PHONE_ONLY -> "Saved on phone only"
    ProofBackupStatus.PENDING_BACKUP -> "Pending backup"
    ProofBackupStatus.WAITING_FOR_WIFI -> "Waiting for Wi-Fi"
    ProofBackupStatus.BACKING_UP -> "Backing up"
    ProofBackupStatus.BACKED_UP -> "Backed up"
    ProofBackupStatus.BACKUP_FAILED -> "Backup failed"
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
