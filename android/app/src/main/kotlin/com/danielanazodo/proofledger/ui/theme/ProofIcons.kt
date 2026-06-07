package com.danielanazodo.proofledger.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Small app-owned outline icon set based on Lucide's 24dp geometry.
 * Keeping the vectors local gives the navigation a consistent visual weight
 * without adding another runtime dependency.
 */
object ProofIcons {
    val Home by lazy {
        outlineIcon(
            "Home",
            "M3 10.5 L12 3 L21 10.5 M5 9 V21 H19 V9 M9 21 V14 H15 V21",
        )
    }
    val Records by lazy {
        outlineIcon(
            "Records",
            "M4 5 A2 2 0 0 1 6 3 H18 A2 2 0 0 1 20 5 V19 A2 2 0 0 1 18 21 H6 A2 2 0 0 1 4 19 Z M4 8 H20 M8 12 H16 M8 16 H14",
        )
    }
    val Reports by lazy {
        outlineIcon(
            "Reports",
            "M4 20 V10 M10 20 V4 M16 20 V13 M22 20 H2",
        )
    }
    val People by lazy {
        outlineIcon(
            "People",
            "M16 21 V19 A4 4 0 0 0 12 15 H6 A4 4 0 0 0 2 19 V21 M9 11 A4 4 0 1 0 9 3 A4 4 0 0 0 9 11 M22 21 V19 A4 4 0 0 0 19 15.13 M16 3.13 A4 4 0 0 1 16 10.87",
        )
    }
    val Settings by lazy {
        outlineIcon(
            "Settings",
            "M12 15.5 A3.5 3.5 0 1 0 12 8.5 A3.5 3.5 0 0 0 12 15.5 M19.4 15 A1.7 1.7 0 0 0 19.74 16.88 L19.8 16.94 A2 2 0 0 1 19.8 19.77 L19.77 19.8 A2 2 0 0 1 16.94 19.8 L16.88 19.74 A1.7 1.7 0 0 0 15 19.4 A1.7 1.7 0 0 0 14 21 V21.1 A2 2 0 0 1 12 23 H11.96 A2 2 0 0 1 9.96 21.1 V21 A1.7 1.7 0 0 0 8.96 19.4 A1.7 1.7 0 0 0 7.08 19.74 L7.02 19.8 A2 2 0 0 1 4.19 19.8 L4.16 19.77 A2 2 0 0 1 4.16 16.94 L4.22 16.88 A1.7 1.7 0 0 0 4.56 15 A1.7 1.7 0 0 0 3 14 H2.9 A2 2 0 0 1 1 12 V11.96 A2 2 0 0 1 2.9 9.96 H3 A1.7 1.7 0 0 0 4.56 8.96 A1.7 1.7 0 0 0 4.22 7.08 L4.16 7.02 A2 2 0 0 1 4.16 4.19 L4.19 4.16 A2 2 0 0 1 7.02 4.16 L7.08 4.22 A1.7 1.7 0 0 0 8.96 4.56 A1.7 1.7 0 0 0 9.96 3 V2.9 A2 2 0 0 1 11.96 1 H12 A2 2 0 0 1 14 2.9 V3 A1.7 1.7 0 0 0 15 4.56 A1.7 1.7 0 0 0 16.88 4.22 L16.94 4.16 A2 2 0 0 1 19.77 4.16 L19.8 4.19 A2 2 0 0 1 19.8 7.02 L19.74 7.08 A1.7 1.7 0 0 0 19.4 8.96 A1.7 1.7 0 0 0 21 9.96 H21.1 A2 2 0 0 1 23 11.96 V12 A2 2 0 0 1 21.1 14 H21 A1.7 1.7 0 0 0 19.4 15",
        )
    }
    val Projects by lazy {
        outlineIcon(
            "Projects",
            "M3 21 H21 M5 21 V6 A2 2 0 0 1 7 4 H17 A2 2 0 0 1 19 6 V21 M9 8 H10 M14 8 H15 M9 12 H10 M14 12 H15 M10 21 V17 H14 V21",
        )
    }
    val Plus by lazy { outlineIcon("Add", "M12 5 V19 M5 12 H19") }
    val Search by lazy {
        outlineIcon("Search", "M11 19 A8 8 0 1 0 11 3 A8 8 0 0 0 11 19 M21 21 L16.65 16.65")
    }
    val Tag by lazy {
        outlineIcon("Tag", "M20.59 13.41 L11 3.83 V3 H4 V10 H4.83 L14.41 19.59 A2 2 0 0 0 17.24 19.59 L20.59 16.24 A2 2 0 0 0 20.59 13.41 M7.5 6.5 H7.51")
    }
}

private fun outlineIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.75f,
        )
    }.build()
