package com.danielanazodo.proofledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardOptions
import com.danielanazodo.proofledger.ui.navigation.MainTab
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofAmber
import com.danielanazodo.proofledger.ui.theme.ProofBorder
import com.danielanazodo.proofledger.ui.theme.ProofGrayDot
import com.danielanazodo.proofledger.ui.theme.ProofIcons
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.theme.ProofLedgerDimens
import com.danielanazodo.proofledger.ui.theme.SurfaceCard
import com.danielanazodo.proofledger.ui.theme.SurfacePanel
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick

@Composable
fun ProofLedgerMainScaffold(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { ProofLedgerBottomNav(selectedTab, onTabSelected) },
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            topBar?.invoke()
            content(Modifier.weight(1f))
        }
    }
}

@Composable
fun ProofLedgerScreen(
    title: String,
    modifier: Modifier = Modifier,
    backLabel: String = "Back",
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(modifier = modifier.padding(padding)) {
            PageHeader(title = title, backLabel = backLabel, onBack = onBack, action = action)
            content(Modifier.weight(1f))
        }
    }
}

@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    backLabel: String = "Back",
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard)
            .padding(
                start = ProofLedgerDimens.headerHorizontal,
                end = ProofLedgerDimens.headerHorizontal,
                top = ProofLedgerDimens.headerTop,
                bottom = ProofLedgerDimens.headerBottom,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            onBack?.let {
                val back = rememberDebouncedClick(onClick = it)
                IconButton(onClick = back) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = backLabel,
                        tint = NeutralMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = NeutralInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = NeutralMuted)
                }
            }
        }
        action?.invoke()
    }
}

@Composable
fun IconCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconColor: Color = NeutralInk,
    borderColor: Color = ProofBorder,
    containerColor: Color = SurfaceCard,
) {
    val click = rememberDebouncedClick(onClick = onClick)
    IconButton(
        onClick = click,
        modifier = modifier.size(ProofLedgerDimens.iconTouchTarget),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(containerColor)
                .border(1.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconColor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun ProofLedgerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    leadingIcon: ImageVector? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (leadingIcon == null) {
            Text(
                text = label,
                color = NeutralMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = if (leadingIcon != null) {
                { Text(label, color = NeutralMuted, style = MaterialTheme.typography.bodyMedium) }
            } else {
                null
            },
            singleLine = singleLine,
            minLines = minLines,
            leadingIcon = leadingIcon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = NeutralMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(ProofLedgerDimens.controlRadius),
            colors = proofTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (singleLine) {
                        Modifier.height(ProofLedgerDimens.controlHeight)
                    } else {
                        Modifier.heightIn(min = ProofLedgerDimens.controlHeight)
                    },
                ),
        )
    }
}

@Composable
fun ProofLedgerFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(ProofLedgerDimens.chipRadius)
    val click = rememberDebouncedClick(onClick = onClick)
    val background = if (selected) ProofLime.copy(alpha = 0.28f) else SurfaceCard
    val borderColor = if (selected) NeutralInk.copy(alpha = 0.22f) else ProofBorder
    val textColor = if (selected) NeutralInk else NeutralMuted

    Box(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = click)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
fun proofTextFieldColors(): TextFieldColors =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = NeutralInk,
        unfocusedTextColor = NeutralInk,
        focusedContainerColor = SurfaceCard,
        unfocusedContainerColor = SurfaceCard,
        disabledContainerColor = SurfaceCard,
        focusedBorderColor = ProofBorder,
        unfocusedBorderColor = ProofBorder,
        focusedLabelColor = NeutralMuted,
        unfocusedLabelColor = NeutralMuted,
        cursorColor = NeutralInk,
    )

@Composable
fun ProofLedgerBottomNav(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SurfaceCard)) {
        HorizontalDivider(color = ProofBorder.copy(alpha = 0.65f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 4.dp, bottom = 4.dp),
        ) {
            MainTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val select = rememberDebouncedClick { onTabSelected(tab) }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(ProofLedgerDimens.controlRadius))
                        .clickable(onClick = select)
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = tab.label,
                        tint = if (selected) NeutralInk else ProofGrayDot,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        tab.label,
                        color = if (selected) NeutralInk else NeutralMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                    Box(
                        modifier = Modifier
                            .size(width = 20.dp, height = 2.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (selected) ProofLime else Color.Transparent),
                    )
                }
            }
        }
    }
}

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.Home -> ProofIcons.Home
    MainTab.Records -> ProofIcons.Records
    MainTab.Projects -> ProofIcons.Projects
    MainTab.People -> ProofIcons.People
    MainTab.Settings -> ProofIcons.Settings
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(bottom = 10.dp),
        color = NeutralMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
    )
}

@Composable
fun GroupedPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ProofLedgerDimens.panelRadius))
            .background(SurfaceCard)
            .border(
                1.dp,
                ProofBorder.copy(alpha = 0.72f),
                RoundedCornerShape(ProofLedgerDimens.panelRadius),
            ),
        content = content,
    )
}

@Composable
fun SummaryPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ProofLedgerDimens.panelRadius))
            .background(SurfacePanel)
            .padding(ProofLedgerDimens.panelPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun MetricLine(label: String, value: String, dot: Color = ProofGrayDot) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(92.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(color = dot)
            Text(label.uppercase(), color = NeutralMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        MoneyAmount(
            text = value,
            color = NeutralInk,
            preferredSize = 24.sp,
            minimumSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(6.dp).clip(CircleShape).background(color))
}

@Composable
fun ProofStatusDot(status: String?) {
    val color = when (status?.lowercase()) {
        "synced", "backed up", "proof attached" -> ProofLime
        "failed", "backup failed", "missing proof", "sync issue" -> ProofAmber
        else -> ProofGrayDot
    }
    StatusDot(color = color)
}

@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(ProofLedgerDimens.controlHeight),
        shape = RoundedCornerShape(ProofLedgerDimens.controlRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = NeutralInk,
            contentColor = SurfaceCard,
            disabledContainerColor = ProofGrayDot,
            disabledContentColor = SurfaceCard,
        ),
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = ProofLime,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = NeutralInk,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(ProofLedgerDimens.controlHeight),
        shape = RoundedCornerShape(ProofLedgerDimens.controlRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceCard,
            contentColor = contentColor,
            disabledContainerColor = SurfacePanel,
            disabledContentColor = NeutralMuted,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, ProofBorder),
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ProofLedgerConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ProofLedgerDimens.panelRadius))
                .background(SurfaceCard)
                .border(1.dp, ProofBorder, RoundedCornerShape(ProofLedgerDimens.panelRadius))
                .padding(ProofLedgerDimens.panelPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    color = NeutralInk,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    message,
                    color = NeutralMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuietButton(
                    text = dismissText,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                PrimaryActionButton(
                    text = confirmText,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = NeutralMuted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = NeutralInk, style = MaterialTheme.typography.bodyMedium)
    }
}

fun SyncLabel(syncStatus: String): String =
    when (syncStatus) {
        "SYNCED" -> "Synced"
        "PENDING_UPLOAD", "PENDING_UPDATE", "PENDING_DELETE" -> "Waiting to sync"
        "FAILED" -> "Sync issue"
        else -> syncStatus.lowercase().replace("_", " ")
    }

@Composable
fun VerticalSpacer(height: Int) {
    Spacer(Modifier.height(height.dp))
}
