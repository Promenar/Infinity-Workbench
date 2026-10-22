package com.promenar.nexara.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Toll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.domain.generation.GenerationProgress
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraMotion

/**
 * 生成中的任务进度：固定在标题栏下方的居中胶囊。
 * 左侧呼吸点表示任务仍在运行，尚无数值时旁边显示“执行中”；工具轮次、工具调用与累计 Token 各占一段，数值为 0 的段不显示，
 * 段与段之间只用间距和图标区分，窄屏大字号时整段换行；数值变化时纵向滚动替换，新段出现时横向展开。Token 为估算时数值前加“≈”。
 */
@Composable
internal fun ChatGenerationProgressBar(progress: GenerationProgress) {
    val hasMetrics = progress.rounds > 0 || progress.calls > 0 || progress.tokens > 0
    val tokens = ChatViewModel.formatTokenCount(progress.tokens.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
    val description = buildList {
        add(stringResource(R.string.chat_progress_running))
        if (progress.rounds > 0) add(pluralStringResource(R.plurals.chat_progress_rounds, progress.rounds, progress.rounds))
        if (progress.calls > 0) add(pluralStringResource(R.plurals.chat_progress_calls, progress.calls, progress.calls))
        if (progress.tokens > 0) add(
            stringResource(if (progress.estimated) R.string.chat_progress_tokens_estimated_cd else R.string.chat_progress_tokens_cd, tokens),
        )
    }.joinToString(stringResource(R.string.chat_progress_separator))
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .testTag(UiTags.CHAT_GENERATION_PROGRESS),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 窄屏大字号下各段整体换行，胶囊随之变为圆角矩形，不截断数值。
            FlowRow(
                modifier = Modifier
                    .heightIn(min = 28.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clearAndSetSemantics { contentDescription = description },
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                RunningDot()
                // 有了具体数值后只保留呼吸点表示运行中，把宽度让给数值。
                AnimatedVisibility(
                    visible = !hasMetrics,
                    enter = expandHorizontally(tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate)) +
                        fadeIn(tween(NexaraMotion.DurationMedium)),
                    exit = shrinkHorizontally(tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate)) +
                        fadeOut(tween(NexaraMotion.DurationShort)),
                ) {
                    Text(
                        stringResource(R.string.chat_progress_running),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                ProgressSegment(visible = progress.rounds > 0, icon = Icons.Rounded.Sync, value = "${progress.rounds}",
                    unit = pluralStringResource(R.plurals.chat_progress_rounds_unit, progress.rounds))
                ProgressSegment(visible = progress.calls > 0, icon = Icons.Rounded.Build, value = "${progress.calls}",
                    unit = pluralStringResource(R.plurals.chat_progress_calls_unit, progress.calls))
                ProgressSegment(visible = progress.tokens > 0, icon = Icons.Rounded.Toll,
                    value = if (progress.estimated) "≈$tokens" else tokens,
                    unit = stringResource(R.string.chat_progress_tokens_unit))
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun RunningDot() {
    val pulse by rememberInfiniteTransition(label = "chat_progress_pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "chat_progress_pulse_alpha",
    )
    Box(Modifier.size(6.dp).alpha(pulse).background(MaterialTheme.colorScheme.primary, CircleShape))
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
                label = "chat_progress_value",
            ) { shown ->
                Text(
                    shown,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
