package com.danielanazodo.proofledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.ui.format.displayDate
import com.danielanazodo.proofledger.ui.format.signedNaira
import com.danielanazodo.proofledger.ui.screens.counterpartyTypeLabel
import com.danielanazodo.proofledger.ui.theme.MoneyOutRed
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofAmber
import com.danielanazodo.proofledger.ui.theme.ProofBorder
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.theme.ProofLedgerDimens

@Composable
fun RecordListRow(
    transaction: LocalTransactionEntity,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick(transaction.id) }
                .padding(
                    horizontal = ProofLedgerDimens.rowHorizontal,
                    vertical = ProofLedgerDimens.rowVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (transaction.syncStatus.name == "FAILED") {
                Box(Modifier.width(2.dp).background(ProofAmber))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    transaction.counterpartyName ?: if (transaction.direction == "money_in") "Money in" else "Money out",
                    color = NeutralInk,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${displayDate(transaction.transactionDate)} · ${recordDirectionLabel(transaction.direction)} · ${SyncLabel(transaction.syncStatus.name)}",
                    color = NeutralMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(
                modifier = Modifier.width(148.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MoneyAmount(
                    text = signedNaira(transaction.amountMinor, transaction.direction),
                    color = if (transaction.direction == "money_in") NeutralInk else MoneyOutRed,
                    preferredSize = MaterialTheme.typography.titleSmall.fontSize,
                    minimumSize = MaterialTheme.typography.labelSmall.fontSize,
                    modifier = Modifier.fillMaxWidth(),
                )
                StatusDot(color = if (transaction.direction == "money_in") ProofLime else NeutralMuted)
            }
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .height(1.dp)
                    .background(ProofBorder)
            )
        }
    }
}

@Composable
fun PersonListRow(
    counterparty: LocalCounterpartyEntity,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick(counterparty.id) }
                .padding(
                    horizontal = ProofLedgerDimens.rowHorizontal,
                    vertical = ProofLedgerDimens.rowVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    counterparty.name,
                    color = NeutralInk,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${counterpartyTypeLabel(counterparty.type)} · ${SyncLabel(counterparty.syncStatus.name)}",
                    color = NeutralMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ProofStatusDot(status = SyncLabel(counterparty.syncStatus.name))
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .height(1.dp)
                    .background(ProofBorder)
            )
        }
    }
}

fun recordDirectionLabel(direction: String): String =
    if (direction == "money_out") "Money out" else "Money in"
