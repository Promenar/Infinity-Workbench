package com.promenar.nexara.ui.renderer

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

@Composable
fun nexaraMarkdownColors(
    textColor: Color = NexaraColors.OnBackground
): MarkdownColors = markdownColor(
    text = textColor,
    codeBackground = Color.Transparent,
    inlineCodeBackground = NexaraColors.SurfaceHigh,
    dividerColor = NexaraColors.OutlineVariant,
    tableBackground = NexaraColors.SurfaceContainer,
)

@Composable
fun nexaraMarkdownTypography(
    baseFontSize: Int = 13,
    fontStyle: FontStyle? = null
): MarkdownTypography {
    val base = baseFontSize.sp
    val h1 = (baseFontSize * 1.2).sp
    val h2 = (baseFontSize * 1.15).sp
    val h3 = (baseFontSize * 1.1).sp
    val h4 = (baseFontSize * 1.1).sp
    val h5 = base
    val h6 = (baseFontSize * 0.9).sp

    return markdownTypography(
        h1 = NexaraTypography.headlineLarge.copy(
            fontSize = h1,
            lineHeight = (h1.value * 1.4).sp,
            fontStyle = fontStyle,
        ),
        h2 = NexaraTypography.headlineMedium.copy(
            fontSize = h2,
            lineHeight = (h2.value * 1.4).sp,
            fontStyle = fontStyle,
        ),
        h3 = NexaraTypography.headlineMedium.copy(
            fontSize = h3,
            lineHeight = (h3.value * 1.4).sp,
            fontStyle = fontStyle,
        ),
        h4 = NexaraTypography.headlineMedium.copy(
            fontSize = h4,
            fontWeight = FontWeight.SemiBold,
            lineHeight = (h4.value * 1.4).sp,
            fontStyle = fontStyle,
        ),
        h5 = NexaraTypography.headlineMedium.copy(
            fontSize = h5,
            fontWeight = FontWeight.Medium,
            lineHeight = (h5.value * 1.4).sp,
            fontStyle = fontStyle,
        ),
        h6 = NexaraTypography.headlineMedium.copy(
            fontSize = h6,
            fontWeight = FontWeight.Medium,
            lineHeight = (h6.value * 1.4).sp,
            fontStyle = fontStyle,
        ),
        text = NexaraTypography.bodyMedium.copy(
            fontSize = base,
            lineHeight = (baseFontSize * 1.6).sp,
            letterSpacing = 0.01.em,
            fontStyle = fontStyle,
        ),
        code = NexaraTypography.bodySmall.copy(
            fontSize = (baseFontSize - 1).sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = (baseFontSize * 1.4).sp,
            fontStyle = fontStyle,
        ),
        inlineCode = NexaraTypography.bodySmall.copy(
            fontSize = base,
            fontFamily = FontFamily.Monospace,
            lineHeight = (baseFontSize * 1.3).sp,
            fontStyle = fontStyle,
        ),
        paragraph = NexaraTypography.bodyMedium.copy(
            fontSize = base,
            lineHeight = (baseFontSize * 1.6).sp,
            fontStyle = fontStyle,
        ),
        quote = NexaraTypography.bodyMedium.copy(
            fontSize = base,
            lineHeight = (baseFontSize * 1.5).sp,
            fontStyle = fontStyle,
        ),
    )
}
