package com.danielanazodo.proofledger.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.danielanazodo.proofledger.data.proof.SharedProofIntakeItem
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.QuietButton
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick

@Composable
fun SharedProofIntakeScreen(
    item: SharedProofIntakeItem?,
    onMoneyIn: () -> Unit,
    onMoneyOut: () -> Unit,
    onCancel: () -> Unit,
) {
    val moneyIn = rememberDebouncedClick(onClick = onMoneyIn)
    val moneyOut = rememberDebouncedClick(onClick = onMoneyOut)
    val cancel = rememberDebouncedClick(onClick = onCancel)
    BackHandler(onBack = cancel)
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = cancel) { Text("Cancel") }
            Text("Add shared proof", style = MaterialTheme.typography.headlineSmall)
            if (item == null) {
                Text(
                    "This shared proof is no longer available.",
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                PrimaryActionButton(text = "Back", onClick = cancel)
            } else {
                GroupedPanel {
                    AsyncImage(
                        model = item.localUri,
                        contentDescription = "Shared payment proof preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .padding(12.dp),
                    )
                }
                Text(
                    "Choose how to save this proof.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeutralMuted,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PrimaryActionButton(
                        text = "Money in",
                        onClick = moneyIn,
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.ArrowDownward,
                    )
                    QuietButton(
                        text = "Money out",
                        onClick = moneyOut,
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.ArrowUpward,
                    )
                }
            }
        }
    }
}
