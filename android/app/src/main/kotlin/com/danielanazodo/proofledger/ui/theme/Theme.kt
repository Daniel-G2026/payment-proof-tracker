package com.danielanazodo.proofledger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val ProofLedgerColors = lightColorScheme(
    primary = MoneyInGreen,
    onPrimary = NeutralPaper,
    secondary = MoneyOutRed,
    onSecondary = NeutralPaper,
    background = NeutralPaper,
    onBackground = NeutralInk,
    surface = SurfaceCard,
    onSurface = NeutralInk,
    surfaceVariant = NeutralPaper,
    onSurfaceVariant = NeutralMuted,
)

@Composable
fun ProofLedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ProofLedgerColors,
        typography = ProofLedgerTypography,
        content = content,
    )
}
