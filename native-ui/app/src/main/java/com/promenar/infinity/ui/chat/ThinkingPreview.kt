package com.promenar.infinity.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.withFrameNanos
import com.promenar.infinity.ui.common.rememberStreamAnimationsEnabled
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/** 思考预览把平滑到达速率交给标题微光；只在绘制阶段读取，不触发重组。 */
internal class ThinkingRateHolder {
    @Volatile
    var value: Float = 0f
}

/** 新放出的字符按 [thinkingFadeAlpha] 渐显；[firstIndex] 为 [text] 首字符在全文中的位置。 */
internal fun thinkingFadedText(text: String, firstIndex: Int, shown: Float, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val fadeStart = (shown - ThinkingPreviewTuning.FADE_IN_CHARS).toInt() - firstIndex
    for (i in fadeStart.coerceAtLeast(0) until text.length) {
        val alpha = thinkingFadeAlpha(firstIndex + i, shown)
        if (alpha < 1f) addStyle(SpanStyle(color = color.copy(alpha = color.alpha * alpha)), i, i + 1)
    }
}

/**
 * 折叠思考块里的滚动预览，始终只占一行高度。
 * 平滑速率低于阈值时单行匀速追赶末尾；高于阈值时按行翻滚；两种呈现交叉淡化。
 * 系统关闭动画（或预览模式）时直接显示末尾文字。
 */
@Composable
internal fun RowScope.ThinkingPreview(reasoning: String, rate: ThinkingRateHolder) {
    val style = InfinityTypography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    if (rememberStreamAnimationsEnabled() != true) {
        StaticThinkingTail(reasoning, style, color)
        return
    }
    val measurer = rememberTextMeasurer()
    val engine = remember(measurer, style) {
        ThinkingPreviewEngine { value -> measurer.measure(value, style, softWrap = false, maxLines = 1).size.width.toFloat() }
            .also { it.update(reasoning, System.nanoTime() / 1_000_000) }
    }
    val latest by rememberUpdatedState(reasoning)
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(engine) {
        snapshotFlow { latest }.collectLatest {
            do {
                withFrameNanos { nanos ->
                    engine.update(latest, nanos / 1_000_000)
                    rate.value = engine.smoothedRate
                    tick++
                }
            } while (engine.isBusy)
        }
    }
    BoxWithConstraints(Modifier.weight(1f).clipToBounds().testTag("CHAT_THINKING_PREVIEW")) {
        engine.setViewport(constraints.maxWidth.toFloat())
        tick // 每帧重组一次，读取引擎当前状态
        val blend by animateFloatAsState(
            targetValue = if (engine.mode == ThinkingPreviewMode.LINE_ROLL) 1f else 0f,
            animationSpec = tween(ThinkingPreviewTuning.MODE_CROSS_FADE_MS),
            label = "thinkingPreviewMode",
        )
        if (blend < 1f) SingleLineLayer(engine, constraints.maxWidth.toFloat(), style, color, Modifier.graphicsLayer { alpha = 1f - blend })
        if (blend > 0f) LineRollLayer(engine, style, color, Modifier.graphicsLayer { alpha = blend })
    }
}

/** 单行：窗口右端随已放出位置连续左移，换算到像素级，新字符从右缘渐显进入，左缘保留渐隐遮罩。 */
@Composable
private fun SingleLineLayer(engine: ThinkingPreviewEngine, viewport: Float, style: TextStyle, color: Color, modifier: Modifier) {
    val window = engine.singleLineWindow()
    val index = engine.shownIndex
    val fraction = (engine.shown - index).coerceIn(0f, 1f)
    val prefixWidth = engine.width(engine.substring(window.first, index))
    val nextWidth = engine.width(engine.substring(window.first, window.last + 1)) - prefixWidth
    val textWidth = prefixWidth + fraction * nextWidth
    val overflowing = textWidth > viewport
    val offsetX = if (overflowing) viewport - textWidth else 0f
    Box(modifier.fadingReasoningTail(enabled = overflowing)) {
        Text(
            text = thinkingFadedText(engine.substring(window.first, window.last + 1), window.first, engine.shown, color),
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = style,
            color = color,
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .offset { IntOffset(offsetX.roundToInt(), 0) },
        )
    }
}

/** 按行翻滚：当前行原地写满，换行时整行上移、新行自下方进入；不使用左侧遮罩，行首完整显示。 */
@Composable
private fun LineRollLayer(engine: ThinkingPreviewEngine, style: TextStyle, color: Color, modifier: Modifier) {
    val seq = engine.rollSeq
    val progress = remember { Animatable(1f) }
    LaunchedEffect(seq) {
        if (seq > 0) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(ThinkingPreviewTuning.LINE_ROLL_MS))
        }
    }
    val (lineStart, visible) = engine.visibleLine()
    val moving = progress.value < 1f
    Box(modifier) {
        if (moving) {
            Text(
                text = engine.rolledOutLine,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                style = style,
                color = color,
                modifier = Modifier.graphicsLayer {
                    translationY = -size.height * progress.value
                    alpha = 1f - progress.value
                },
            )
        }
        Text(
            text = thinkingFadedText(visible, lineStart, engine.shown, color),
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = style,
            color = color,
            modifier = Modifier.graphicsLayer {
                if (moving) {
                    translationY = size.height * (1f - progress.value)
                    alpha = progress.value
                }
            },
        )
    }
}

/** 系统关闭动画时：不滚动、不渐显，直接显示思考内容的末尾。 */
@Composable
private fun RowScope.StaticThinkingTail(reasoning: String, style: TextStyle, color: Color) {
    val tail = remember(reasoning) { streamingReasoningPreview(reasoning) }
    val scroll = rememberScrollState(initial = Int.MAX_VALUE)
    LaunchedEffect(tail) {
        snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
    }
    Box(Modifier.weight(1f).fadingReasoningTail(enabled = scroll.maxValue > 0).testTag("CHAT_THINKING_PREVIEW")) {
        Text(
            text = tail,
            softWrap = false,
            maxLines = 1,
            modifier = Modifier.horizontalScroll(scroll, enabled = false),
            style = style,
            color = color,
        )
    }
}
