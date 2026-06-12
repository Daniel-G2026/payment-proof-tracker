package com.danielanazodo.proofledger.ui.theme

import androidx.compose.ui.graphics.Color

val MoneyInGreen = Color(0xFF1E8A5A)
val MoneyOutRed = Color(0xFFD14316)
val NeutralInk = Color(0xFF1A1A1A)
val NeutralPaper = Color(0xFFF7F7F7)
// Muted text still has to pass WCAG AA (~5:1) on NeutralPaper, not just on white.
val NeutralMuted = Color(0xFF6B6B6B)
val NeutralSubtle = Color(0xFFB0B0B0)
val SurfaceCard = Color(0xFFFFFFFF)
val SurfacePanel = Color(0xFFF2F2F2)
val ProofLime = Color(0xFFC9E926)
val ProofBorder = Color(0xFFE5E5E5)
val ProofAmber = Color(0xFFF59E0B)

// Amber for TEXT on light surfaces: bright ProofAmber is ~2:1 contrast and
// belongs on dots/strips only. Deep amber keeps the caution signal at AA (~5:1).
val ProofAmberDeep = Color(0xFFB45309)
val ProofGrayDot = Color(0xFFC0C0C0)
