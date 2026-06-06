package com.danielanazodo.proofledger.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.danielanazodo.proofledger.ui.theme.DmMonoFontFamily
import com.danielanazodo.proofledger.ui.theme.InterFontFamily
import kotlin.math.floor

@Composable
fun MoneyAmount(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    preferredSize: TextUnit = MaterialTheme.typography.headlineSmall.fontSize,
    minimumSize: TextUnit = 13.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
    textAlign: TextAlign = TextAlign.End,
) {
    BoxWithConstraints(modifier = modifier) {
        val resolvedSize = adaptiveMoneyFontSize(
            textLength = text.length,
            availableWidthDp = maxWidth.value,
            preferredSp = preferredSize.value,
            minimumSp = minimumSize.value,
        ).sp
        val amount = buildAnnotatedString {
            text.forEach { character ->
                val numeric = character.isDigit() || character == ',' || character == '.'
                withStyle(
                    SpanStyle(
                        fontFamily = if (numeric) DmMonoFontFamily else InterFontFamily,
                        fontWeight = fontWeight,
                    ),
                ) {
                    append(character)
                }
            }
        }
        Text(
            text = amount,
            color = color,
            fontSize = resolvedSize,
            lineHeight = (resolvedSize.value * 1.12f).sp,
            fontWeight = fontWeight,
            textAlign = textAlign,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun adaptiveMoneyFontSize(
    textLength: Int,
    availableWidthDp: Float,
    preferredSp: Float,
    minimumSp: Float,
): Float {
    if (textLength <= 0 || availableWidthDp <= 0f) return preferredSp
    val estimatedWidthAtPreferred = textLength * preferredSp * 0.62f
    if (estimatedWidthAtPreferred <= availableWidthDp) return preferredSp
    val scaled = preferredSp * (availableWidthDp / estimatedWidthAtPreferred)
    return floor(scaled.coerceIn(minimumSp, preferredSp) * 2f) / 2f
}
