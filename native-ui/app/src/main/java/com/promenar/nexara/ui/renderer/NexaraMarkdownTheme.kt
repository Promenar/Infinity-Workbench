package com.promenar.nexara.ui.renderer

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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
fun nexaraMarkdownColors(): MarkdownColors = markdownColor(
    text = NexaraColors.OnBackground,
    codeBackground = Color.Transparent,
    inlineCodeBackground = NexaraColors.SurfaceHigh,
    dividerColor = NexaraColors.OutlineVariant,
    tableBackground = NexaraColors.SurfaceContainer,
)

@Composable
fun nexaraMarkdownTypography(): MarkdownTypography = markdownTypography(
    h1 = NexaraTypography.headlineLarge,
    h2 = NexaraTypography.headlineMedium,
    h3 = NexaraTypography.headlineMedium.copy(fontSize = 18.sp, lineHeight = 26.sp),
    h4 = NexaraTypography.headlineMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    h5 = NexaraTypography.headlineMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    h6 = NexaraTypography.headlineMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    text = NexaraTypography.bodyMedium.copy(
        lineHeight = 26.sp,
        letterSpacing = 0.01.em
    ),
    code = NexaraTypography.bodySmall.copy(fontFamily = FontFamily.Monospace),
)
