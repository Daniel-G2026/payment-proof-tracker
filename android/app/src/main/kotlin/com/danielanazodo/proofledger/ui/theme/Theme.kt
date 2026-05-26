package com.danielanazodo.proofledger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val ProofLedgerColors = lightColorScheme(
    primary = NeutralInk,
    onPrimary = SurfaceCard,
    secondary = ProofLime,
    onSecondary = NeutralInk,
    background = NeutralPaper,
    onBackground = NeutralInk,
    surface = SurfaceCard,
    onSurface = NeutralInk,
    surfaceVariant = SurfacePanel,
    onSurfaceVariant = NeutralMuted,
    outline = ProofBorder,
    error = MoneyOutRed,
)

@Composable
fun ProofLedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ProofLedgerColors,
        typography = ProofLedgerTypography,
        content = content,
    )
}
