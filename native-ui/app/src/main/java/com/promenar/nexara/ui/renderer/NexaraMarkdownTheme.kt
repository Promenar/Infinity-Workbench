package com.promenar.nexara.ui.renderer

import com.promenar.nexara.ui.theme.NexaraMonospace
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.promenar.nexara.ui.theme.NexaraInputLineBreak
import com.promenar.nexara.ui.theme.NexaraReadingLineBreak
import com.promenar.nexara.ui.theme.NexaraTypography

@Composable
fun nexaraMarkdownColors(
    textColor: Color = MaterialTheme.colorScheme.onSurface
): MarkdownColors = markdownColor(
    text = textColor,
    codeBackground = MaterialTheme.colorScheme.surfaceContainerLow,
    inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
    dividerColor = MaterialTheme.colorScheme.outlineVariant,
    tableBackground = MaterialTheme.colorScheme.surfaceContainer,
)

internal fun nexaraMarkdownBodyStyle(baseFontSize: Int, fontStyle: FontStyle? = null): TextStyle =
    NexaraTypography.bodyMedium.copy(
        fontSize = baseFontSize.sp,
        lineHeight = (baseFontSize * 1.6).sp,
        letterSpacing = 0.01.em,
        fontStyle = fontStyle,
        lineBreak = NexaraReadingLineBreak,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )

internal fun nexaraMarkdownHeadingStyle(level: Int, baseFontSize: Int, fontStyle: FontStyle? = null): TextStyle {
    val multiplier = when (level) { 1 -> 1.35; 2 -> 1.2; 3 -> 1.1; else -> 1.0 }
    val size = (baseFontSize * multiplier).sp
    return NexaraTypography.titleMedium.copy(
        fontSize = size,
        fontWeight = FontWeight.SemiBold,
        lineHeight = (size.value * 1.35).sp,
        fontStyle = fontStyle,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )
}

internal fun nexaraMarkdownCodeStyle(fontStyle: FontStyle? = null): TextStyle =
    NexaraTypography.bodySmall.copy(
        fontSize = 13.sp,
        fontFamily = NexaraMonospace,
        lineHeight = 20.sp,
        fontStyle = fontStyle,
        lineBreak = NexaraInputLineBreak,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )

@Composable
fun nexaraMarkdownTypography(
    baseFontSize: Int = 15,
    fontStyle: FontStyle? = null,
): MarkdownTypography {
    val body = nexaraMarkdownBodyStyle(baseFontSize, fontStyle)
    return markdownTypography(
        h1 = nexaraMarkdownHeadingStyle(1, baseFontSize, fontStyle),
        h2 = nexaraMarkdownHeadingStyle(2, baseFontSize, fontStyle),
        h3 = nexaraMarkdownHeadingStyle(3, baseFontSize, fontStyle),
        h4 = nexaraMarkdownHeadingStyle(4, baseFontSize, fontStyle),
        h5 = nexaraMarkdownHeadingStyle(5, baseFontSize, fontStyle),
        h6 = nexaraMarkdownHeadingStyle(6, baseFontSize, fontStyle),
        text = body,
        code = nexaraMarkdownCodeStyle(fontStyle),
        inlineCode = body.copy(fontFamily = NexaraMonospace),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        quote = body,
    )
}

fun nexaraSyntaxTheme(isDark: Boolean): dev.snipme.highlights.model.SyntaxTheme {
    val base = dev.snipme.highlights.model.SyntaxThemes.atom(isDark)
    return if (isDark) {
        base.copy(punctuation = 0xFFCBD5E1.toInt())
    } else {
        base
    }
}
