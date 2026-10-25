package com.promenar.nexara.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraMetaSeparator
import com.promenar.nexara.ui.theme.NexaraElevation
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography
import java.text.NumberFormat
import java.util.Locale

/** 浮卡展示所需的全部数据；时间文本由调用方按本地时区格式化。 */
internal data class GenerationStatsCardModel(
    val modelName: String,
    val providerName: String?,
    val generatedAt: String,
    val summary: GenerationStatsSummary,
)

/** 待本地化的统计文案：资源 ID 加格式参数。 */
internal data class GenerationStatsText(val resId: Int, val args: List<Any>)

internal fun formatStatsCount(value: Long, locale: Locale): String =
    NumberFormat.getIntegerInstance(locale).format(value)

internal fun formatStatsRate(value: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(value)

/** 不足 1 秒显示毫秒，不足 1 分钟显示一位小数的秒，否则显示分与秒。 */
internal fun formatStatsDuration(durationMs: Long, locale: Locale): GenerationStatsText {
    val safe = durationMs.coerceAtLeast(0)
    return when {
        safe < 1_000 -> GenerationStatsText(R.string.generation_stats_duration_ms, listOf(formatStatsCount(safe, locale)))
        safe < 60_000 -> GenerationStatsText(
            R.string.generation_stats_duration_seconds,
            listOf(formatStatsRate(safe / 1_000.0, locale)),
        )
        else -> {
            val totalSeconds = safe / 1_000
            GenerationStatsText(
                R.string.generation_stats_duration_minutes,
                listOf((totalSeconds / 60).toInt(), (totalSeconds % 60).toInt()),
            )
        }
    }
}

@Composable
private fun GenerationStatsText.resolve(): String = stringResource(resId, *args.toTypedArray())

@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

/** 回复下方统计行：轮次 · 工具调用 · Token · 用时，缺数据的项不显示。 */
@Composable
internal fun replyRunStatsText(stats: ReplyRunStats): String {
    val locale = currentLocale()
    val parts = buildList {
        if (stats.rounds > 0) add(pluralStringResource(R.plurals.run_detail_rounds, stats.rounds, stats.rounds))
        add(pluralStringResource(R.plurals.run_detail_calls, stats.toolCalls, stats.toolCalls))
        stats.tokens?.takeIf { it > 0 }?.let { tokens ->
            val text = com.promenar.nexara.ui.chat.ChatViewModel.formatTokenCount(tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            add(stringResource(R.string.run_detail_tokens, if (stats.estimated) "≈$text" else text))
        }
        stats.durationMs?.let { add(formatStatsDuration(it, locale).resolve()) }
    }
    return parts.joinToString(" $NexaraMetaSeparator ")
}

/**
 * 在锚点上方弹出生成统计浮卡；上方放不下时改在下方，两侧都放不下时放在空间较大的一侧并限制高度、内部滚动。
 * 上下边界避开状态栏、导航栏、刘海与输入法（safeDrawing）并保留边距，浮卡不遮住锚点。点外部或返回键关闭。
 *
 * [anchorBoundsInWindow] 为锚点在窗口中的边界（与 Popup 定位用的锚点坐标一致），未知时只按屏幕高度限制。
 */
@Composable
internal fun GenerationStatsPopup(
    model: GenerationStatsCardModel,
    anchorBoundsInWindow: IntRect?,
    onDismissRequest: () -> Unit,
) {
    val density = LocalDensity.current
    val marginPx = with(density) { NexaraSpacing.Large.roundToPx() }
    val gapPx = with(density) { NexaraSpacing.XSmall.roundToPx() }
    val safeDrawing = WindowInsets.safeDrawing
    val rootHeightPx = LocalView.current.rootView.height
    val topLimitPx = safeDrawing.getTop(density) + marginPx
    val bottomLimitPx = rootHeightPx - safeDrawing.getBottom(density) - marginPx
    val bounds = GenerationStatsPopupBounds(topLimitPx, bottomLimitPx, marginPx, gapPx)
    val provider = remember(bounds) { AboveAnchorPositionProvider(bounds) }
    val configuration = LocalConfiguration.current
    val maxWidth = (configuration.screenWidthDp.dp - NexaraSpacing.Large * 2).coerceAtMost(CardMaxWidth)
    val screenCapPx = with(density) { (configuration.screenHeightDp * 0.7f).dp.roundToPx() }
    val maxHeight = with(density) {
        (anchorBoundsInWindow?.let { bounds.availableHeight(it) } ?: screenCapPx)
            .coerceAtMost(screenCapPx)
            .coerceAtLeast(0)
            .toDp()
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        GenerationStatsCard(
            model = model,
            modifier = Modifier.width(maxWidth).heightIn(max = maxHeight),
            scrollable = true,
        )
    }
}

/** 浮卡可用的竖向范围（窗口坐标，像素）与边距、与锚点的间隔。 */
internal data class GenerationStatsPopupBounds(
    val topLimitPx: Int,
    val bottomLimitPx: Int,
    val marginPx: Int,
    val gapPx: Int,
) {
    fun spaceAbove(anchor: IntRect): Int = (anchor.top - gapPx - topLimitPx).coerceAtLeast(0)
    fun spaceBelow(anchor: IntRect): Int = (bottomLimitPx - anchor.bottom - gapPx).coerceAtLeast(0)

    /** 浮卡最多可用的高度：锚点上下两侧中较大的一侧，保证浮卡不遮住锚点。 */
    fun availableHeight(anchor: IntRect): Int = maxOf(spaceAbove(anchor), spaceBelow(anchor))
}

private val CardMaxWidth = 340.dp
private val CardShape = RoundedCornerShape(16.dp)
private val TileShape = RoundedCornerShape(12.dp)

@Composable
internal fun GenerationStatsCard(
    model: GenerationStatsCardModel,
    modifier: Modifier = Modifier,
    initiallyExpandedBreakdown: Boolean = false,
    scrollable: Boolean = false,
) {
    val locale = currentLocale()
    val summary = model.summary
    val colors = MaterialTheme.colorScheme
    val unavailable = stringResource(R.string.generation_stats_unavailable)
    val unavailableDescription = stringResource(R.string.generation_stats_unavailable_description)
    fun count(value: Long?) = value?.let { formatStatsCount(it, locale) }
    Surface(
        modifier = modifier.testTag(GENERATION_STATS_CARD_TAG),
        shape = CardShape,
        color = colors.surfaceContainer,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = NexaraElevation.Level3,
    ) {
        Column(
            modifier = Modifier
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(NexaraSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
        ) {
            StatsHeader(model, summary.estimated)
            val tps = summary.generationTokensPerSecond?.let { formatStatsRate(it, locale) }
            Row(horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
                val tokenUnit = stringResource(R.string.generation_stats_token_unit)
                StatsTile(stringResource(R.string.generation_stats_input), count(summary.inputTokens), tokenUnit, unavailable, unavailableDescription, Modifier.weight(1f))
                StatsTile(stringResource(R.string.generation_stats_output), count(summary.outputTokens), tokenUnit, unavailable, unavailableDescription, Modifier.weight(1f))
                StatsTile(
                    stringResource(R.string.generation_stats_generation_speed),
                    tps,
                    stringResource(R.string.generation_stats_tps_unit),
                    unavailable,
                    unavailableDescription,
                    Modifier.weight(1f),
                )
            }
            if (summary.rounds > 1) {
                Text(
                    text = stringResource(
                        R.string.generation_stats_metric_description,
                        stringResource(R.string.generation_stats_input),
                        stringResource(R.string.generation_stats_multi_round_input_note),
                    ),
                    style = NexaraTypography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Column {
                summary.cacheReadTokens?.let { StatsRow(stringResource(R.string.generation_stats_cache_read), count(it)!!) }
                summary.uncachedInputTokens?.let { StatsRow(stringResource(R.string.generation_stats_uncached), count(it)!!) }
                summary.reasoningTokens?.let { StatsRow(stringResource(R.string.generation_stats_reasoning), count(it)!!) }
                StatsRow(
                    stringResource(R.string.generation_stats_ttft),
                    summary.timeToFirstTokenMs?.let { formatStatsDuration(it, locale).resolve() },
                    unavailable,
                    unavailableDescription,
                )
                StatsRow(
                    stringResource(R.string.generation_stats_end_to_end_throughput),
                    summary.endToEndTokensPerSecond?.let { stringResource(R.string.generation_stats_tps, formatStatsRate(it, locale)) },
                    unavailable,
                    unavailableDescription,
                )
                StatsRow(
                    stringResource(R.string.generation_stats_end_to_end_time),
                    summary.endToEndMs?.let { formatStatsDuration(it, locale).resolve() },
                    unavailable,
                    unavailableDescription,
                )
                if (summary.rounds > 1) {
                    StatsRow(
                        stringResource(R.string.generation_stats_rounds_label),
                        pluralStringResource(R.plurals.generation_stats_rounds_total, summary.rounds, summary.rounds),
                    )
                }
                if (summary.toolCalls > 0) {
                    StatsRow(
                        stringResource(R.string.generation_stats_tool_calls_label),
                        pluralStringResource(R.plurals.generation_stats_tool_calls_total, summary.toolCalls, summary.toolCalls),
                    )
                }
            }
            summary.runtime?.let { RuntimeBreakdownSection(it, initiallyExpandedBreakdown, locale) }
        }
    }
}

@Composable
private fun StatsHeader(model: GenerationStatsCardModel, estimated: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = model.modelName.ifBlank { stringResource(R.string.generation_stats_title) },
                style = NexaraTypography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            val subtitle = listOfNotNull(model.providerName?.takeIf(String::isNotBlank), model.generatedAt.takeIf(String::isNotBlank))
                .joinToString(" $NexaraMetaSeparator ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = NexaraTypography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (estimated) {
            val description = stringResource(R.string.generation_stats_estimated_description)
            Box(
                modifier = Modifier
                    .padding(start = NexaraSpacing.Small)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.tertiaryContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .clearAndSetSemantics { contentDescription = description },
            ) {
                Text(
                    text = stringResource(R.string.generation_stats_estimated),
                    style = NexaraTypography.labelSmall,
                    color = colors.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun StatsTile(
    label: String,
    value: String?,
    unit: String?,
    unavailable: String,
    unavailableDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spoken = value?.let { if (unit != null) "$it $unit" else it } ?: unavailableDescription
    val description = stringResource(R.string.generation_stats_metric_description, label, spoken)
    Column(
        modifier = modifier
            .clip(TileShape)
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = NexaraSpacing.Small)
            .clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = NexaraTypography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val valueStyle = NexaraTypography.titleMedium.copy(color = colors.onSurface)
        BasicText(
            text = value ?: unavailable,
            style = valueStyle,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = valueStyle.fontSize, stepSize = 0.5.sp),
        )
        if (unit != null) {
            Text(unit, style = NexaraTypography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatsRow(
    label: String,
    value: String?,
    unavailable: String = "",
    unavailableDescription: String = "",
) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.generation_stats_metric_description, label, value ?: unavailableDescription)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = NexaraTypography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            text = value ?: unavailable,
            style = NexaraTypography.bodySmall,
            color = colors.onSurface,
            modifier = Modifier.padding(start = NexaraSpacing.Small),
        )
    }
}

@Composable
private fun RuntimeBreakdownSection(
    breakdown: GenerationRuntimeBreakdown,
    initiallyExpanded: Boolean,
    locale: Locale,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val colors = MaterialTheme.colorScheme
    val title = stringResource(R.string.generation_stats_runtime_breakdown)
    val state = stringResource(if (expanded) R.string.common_state_expanded else R.string.common_state_collapsed)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics(mergeDescendants = true) { stateDescription = state }
                .testTag(GENERATION_STATS_BREAKDOWN_TOGGLE_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = NexaraTypography.labelLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            val parts = listOfNotNull(
                Triple(R.string.generation_stats_runtime_model, breakdown.modelMs, colors.primary),
                // 没有工具调用或没有额外开销时不画 0 时长的空条。
                breakdown.toolMs?.takeIf { it > 0 }?.let { Triple(R.string.generation_stats_runtime_tools, it, colors.tertiary) },
                breakdown.otherMs?.takeIf { it > 0 }?.let { Triple(R.string.generation_stats_runtime_other, it, colors.outline) },
            )
            val total = parts.sumOf { it.second }.coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                parts.forEach { (labelRes, ms, color) ->
                    RuntimeBar(stringResource(labelRes), formatStatsDuration(ms, locale).resolve(), ms.toFloat() / total, color)
                }
            }
        }
    }
}

@Composable
private fun RuntimeBar(label: String, duration: String, fraction: Float, color: Color) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.generation_stats_metric_description, label, duration)
    Column(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = NexaraTypography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(duration, style = NexaraTypography.labelSmall, color = colors.onSurface)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.surfaceContainerHighest),
        ) {
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color),
                )
            }
        }
    }
}

/**
 * 优先放在锚点上方并与锚点末端对齐；上方放不下时放在下方；两侧都放不下时放在空间较大的一侧，
 * 竖向始终处于 [GenerationStatsPopupBounds] 的安全范围内，横向保留屏幕边距。
 */
internal class AboveAnchorPositionProvider(
    private val bounds: GenerationStatsPopupBounds,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = bounds.marginPx
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        }
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val x = preferredX.coerceIn(margin.coerceAtMost(maxX), maxX)
        val height = popupContentSize.height
        val above = anchorBounds.top - bounds.gapPx - height
        val below = anchorBounds.bottom + bounds.gapPx
        val y = when {
            height <= bounds.spaceAbove(anchorBounds) -> above
            height <= bounds.spaceBelow(anchorBounds) -> below
            bounds.spaceAbove(anchorBounds) >= bounds.spaceBelow(anchorBounds) -> maxOf(bounds.topLimitPx, above)
            else -> minOf(below, (bounds.bottomLimitPx - height).coerceAtLeast(bounds.topLimitPx))
        }
        return IntOffset(x, y)
    }
}

internal const val GENERATION_STATS_CARD_TAG = "CHAT_GENERATION_STATS_CARD"
internal const val GENERATION_STATS_BREAKDOWN_TOGGLE_TAG = "CHAT_GENERATION_STATS_BREAKDOWN_TOGGLE"
