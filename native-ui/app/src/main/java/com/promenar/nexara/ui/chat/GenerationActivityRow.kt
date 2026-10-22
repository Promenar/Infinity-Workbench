package com.promenar.nexara.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Toll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.domain.generation.GenerationProgress
import com.promenar.nexara.ui.common.network.rememberAnimationsEnabled
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraMotion

/**
 * 输入框浮岛顶部的活动栏：生成中展开，左侧流光文字表示仍在运行（有计划时显示当前步骤），其后是工具轮次、工具调用与累计 Token。
 * 数值为 0 的段不显示，窄屏大字号时整段换行；数值变化时纵向滚动替换，新段出现时横向展开。Token 为估算时数值前加“≈”。
 * 读屏读完整一句，不逐个读图标与数字。
 */
@Composable
internal fun GenerationActivityContent(
    progress: GenerationProgress,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val running = stringResource(R.string.chat_progress_running)
    val tokens = ChatViewModel.formatTokenCount(progress.tokens.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
    val description = buildList {
        add(label ?: running)
        if (progress.rounds > 0) add(pluralStringResource(R.plurals.chat_progress_rounds, progress.rounds, progress.rounds))
        if (progress.calls > 0) add(pluralStringResource(R.plurals.chat_progress_calls, progress.calls, progress.calls))
        if (progress.tokens > 0) add(
            stringResource(if (progress.estimated) R.string.chat_progress_tokens_estimated_cd else R.string.chat_progress_tokens_cd, tokens),
        )
    }.joinToString(stringResource(R.string.chat_progress_separator))
    FlowRow(
        modifier = modifier
            .testTag(UiTags.CHAT_GENERATION_PROGRESS)
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ShimmerLabel(label ?: running, Modifier.widthIn(max = 168.dp))
        ProgressSegment(visible = progress.rounds > 0, icon = Icons.Rounded.Sync, value = "${progress.rounds}",
            unit = pluralStringResource(R.plurals.chat_progress_rounds_unit, progress.rounds))
        ProgressSegment(visible = progress.calls > 0, icon = Icons.Rounded.Build, value = "${progress.calls}",
            unit = pluralStringResource(R.plurals.chat_progress_calls_unit, progress.calls))
        ProgressSegment(visible = progress.tokens > 0, icon = Icons.Rounded.Toll,
            value = if (progress.estimated) "≈$tokens" else tokens,
            unit = stringResource(R.string.chat_progress_tokens_unit))
    }
}

/** 没有计划时的独立活动栏：一行活动内容，下方一条与输入文字对齐的分隔线。 */
@Composable
internal fun GenerationActivityRow(progress: GenerationProgress, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        GenerationActivityContent(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
    }
}

/**
 * 活动栏文字：12sp 字号配 16sp 行高时，默认行高分配会让中文单位比数字和图标低一截。
 * 去掉字体内边距并把行高居中裁切，数字、中文与图标的视觉中线对齐。
 */
@Composable
private fun activityTextStyle(): TextStyle = MaterialTheme.typography.labelMedium.copy(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both),
)

/** 流光文字：一道高光从左向右扫过；系统关闭动画或静态预览时为普通强调色文字。 */
@Composable
private fun ShimmerLabel(text: String, modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val highlight = MaterialTheme.colorScheme.onSurface
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val shimmerModifier = if (animate) {
        val sweep = rememberInfiniteTransition(label = "activity_shimmer").animateFloat(
            initialValue = -0.4f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Restart),
            label = "activity_shimmer_offset",
        )
        Modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithCache {
                val band = size.width * 0.5f
                onDrawWithContent {
                    drawContent()
                    val x = sweep.value * size.width
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to base, 0.5f to highlight, 1f to base,
                            startX = x - band, endX = x + band,
                        ),
                        blendMode = BlendMode.SrcIn,
                    )
                }
            }
    } else {
        Modifier
    }
    Text(
        text = text,
        modifier = modifier.then(shimmerModifier),
        style = activityTextStyle(),
        fontWeight = FontWeight.Medium,
        color = if (animate) base else MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ProgressSegment(visible: Boolean, icon: ImageVector, value: String, unit: String) {
    AnimatedVisibility(
        visible = visible,
        enter = expandHorizontally(tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate)) +
            fadeIn(tween(NexaraMotion.DurationMedium)),
        exit = shrinkHorizontally(tween(NexaraMotion.DurationShort)) + fadeOut(tween(NexaraMotion.DurationShort)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            AnimatedContent(
                targetState = value,
                transitionSpec = { NexaraMotion.sharedAxisY(forward = true) },
                contentAlignment = Alignment.Center,
                label = "chat_progress_value",
            ) { shown ->
                Text(
                    shown,
                    style = activityTextStyle().copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Text(unit, style = activityTextStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
