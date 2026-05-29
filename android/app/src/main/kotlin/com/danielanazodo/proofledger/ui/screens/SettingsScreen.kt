package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.BuildConfig
import com.danielanazodo.proofledger.data.local.ProofBackupPreference
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.PageHeader
import com.danielanazodo.proofledger.ui.components.ProofLedgerMainScaffold
import com.danielanazodo.proofledger.ui.components.SectionLabel
import com.danielanazodo.proofledger.ui.format.formatCompactCount
import com.danielanazodo.proofledger.ui.navigation.MainTab
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofAmber
import com.danielanazodo.proofledger.ui.theme.ProofBorder
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.SettingsViewModel
import java.util.Locale

@Composable
fun SettingsScreen(
    onProofBackup: () -> Unit,
    onSyncIssues: () -> Unit,
    onSwitchBusiness: () -> Unit,
    onSignedOut: () -> Unit,
    onTabSelected: (MainTab) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signOut = rememberDebouncedClick(onClick = viewModel::signOut)

    LaunchedEffect(state.signedOut) {
        if (state.signedOut) onSignedOut()
    }

    ProofLedgerMainScaffold(
        selectedTab = MainTab.Settings,
        onTabSelected = onTabSelected,
        topBar = { PageHeader(title = "Settings") },
    ) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SettingsSection(title = "Business") {
                SettingsRow(
                    label = "Business name",
                    value = state.business?.name ?: "Not set",
                    enabled = false,
                )
                SettingsRow(
                    label = "Currency",
                    value = state.business?.currency ?: "NGN",
                    enabled = false,
                )
                SettingsRow(
                    label = "Switch business",
                    onClick = onSwitchBusiness,
                    showDivider = false,
                )
            }

            SettingsSection(title = "Sync & backup") {
                SettingsRow(
                    label = "Sync issues",
                    value = if (state.syncIssueCount > 0) {
                        "${formatCompactCount(state.syncIssueCount)} waiting"
                    } else {
                        "None"
                    },
                    valueColor = if (state.syncIssueCount > 0) ProofAmber else NeutralMuted,
                    onClick = onSyncIssues,
                )
                SettingsRow(
                    label = "Proof backup",
                    value = proofBackupValue(state.pendingProofCount, state.estimatedProofBytes),
                    valueColor = if (state.pendingProofCount > 0) ProofAmber else NeutralMuted,
                    onClick = onProofBackup,
                )
                SettingsRow(
                    label = "Backup preference",
                    value = state.backupPreference.label(),
                    enabled = false,
                    showDivider = false,
                )
            }

            SettingsSection(title = "Account") {
                SettingsRow(
                    label = "Name",
                    value = state.profile?.fullName?.takeIf { it.isNotBlank() } ?: "Not set",
                    enabled = false,
                )
                SettingsRow(
                    label = "Email",
                    value = state.profile?.email?.takeIf { it.isNotBlank() } ?: "Not set",
                    enabled = false,
                )
                SettingsRow(
                    label = "Sign out",
                    value = "",
                    valueColor = Color(0xFFEF4444),
                    labelColor = Color(0xFFEF4444),
                    onClick = signOut,
                    showDivider = false,
                    showChevron = false,
                )
            }

            SettingsSection(title = "App") {
                SettingsRow(
                    label = "Storage",
                    value = "On device",
                    enabled = false,
                )
                SettingsRow(
                    label = "Version",
                    value = BuildConfig.VERSION_NAME,
                    enabled = false,
                    showDivider = false,
                )
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        SectionLabel(title)
        GroupedPanel {
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String = "",
    valueColor: Color = NeutralMuted,
    labelColor: Color = NeutralInk,
    enabled: Boolean = true,
    showChevron: Boolean = enabled,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val click = rememberDebouncedClick(onClick = onClick ?: {})
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (enabled && onClick != null) {
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = click)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = label,
                color = labelColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    color = valueColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (showChevron) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = ProofBorder,
                )
            }
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .height(1.dp)
                    .background(ProofBorder.copy(alpha = 0.45f))
            )
        }
    }
}

private fun ProofBackupPreference.label(): String =
    when (this) {
        ProofBackupPreference.WIFI_ONLY -> "Wi-Fi only"
        ProofBackupPreference.MANUAL -> "Manual"
        ProofBackupPreference.IMPORTANT_ONLY -> "Important only"
    }

private fun proofBackupValue(pendingCount: Int, estimatedBytes: Long): String =
    if (pendingCount <= 0) {
        "Up to date"
    } else {
        "${formatCompactCount(pendingCount)} waiting - ${formatBytes(estimatedBytes)}"
    }

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.toInt().coerceAtLeast(0)} KB"
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}
