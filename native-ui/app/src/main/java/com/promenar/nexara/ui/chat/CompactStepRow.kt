package com.promenar.nexara.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.ui.theme.NexaraTypography
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraMetaSeparator
import com.promenar.nexara.ui.common.rememberStreamAnimationsEnabled

/** 相邻步骤依靠行高分隔，正文边界由消息容器添加间距。 */
internal val ChatStepSpacing = 0.dp

/** 渐隐只作用于推理预览，不叠加不透明遮罩，适配浅色与深色主题。 */
@Composable
internal fun Modifier.fadingReasoningTail(enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val mask = MaterialTheme.colorScheme.onSurface.copy(alpha = 1f)
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val edge = (24.dp.toPx() / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        drawRect(
            brush = Brush.horizontalGradient(0f to mask.copy(alpha = 0f), edge to mask, 1f to mask),
            blendMode = BlendMode.DstIn,
        )
    }
}

@Composable
private fun Modifier.stepShimmer(active: Boolean): Modifier {
    if (!active || rememberStreamAnimationsEnabled() != true) return this
    val transition = rememberInfiniteTransition(label = "stepShimmer")
    val phase = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "stepHighlight")
    val highlight = MaterialTheme.colorScheme.onSurface
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val band = 48.dp.toPx()
        val center = -band + (size.width + band * 2) * phase.value
        drawRect(
            brush = Brush.linearGradient(
                listOf(Color.Transparent, highlight, Color.Transparent),
                start = Offset(center - band, 0f), end = Offset(center + band, 0f),
            ),
            blendMode = BlendMode.SrcAtop,
        )
    }
}

/** 44dp 整行触控，放大字号时按文字行高增高。 */
@Composable
internal fun CompactStepRow(
    segments: List<String>,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Int = 13,
    active: Boolean = false,
    progress: Float? = null,
    expanded: Boolean? = null,
    state: String = "",
    clickLabel: String? = null,
    tag: String? = null,
    tone: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    preview: (@Composable RowScope.() -> Unit)? = null,
    failureText: String? = null,
) {
    val style = NexaraTypography.labelLarge.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    )
    // 整步失败时（tone 为 error）整行用错误色；部分失败只把失败段标红，摘要保持中性。
    val textColor = if (tone == MaterialTheme.colorScheme.error) {
        MaterialTheme.colorScheme.error
    } else tone.copy(alpha = 0.85f)
    val visibleSegments = segments.filter(String::isNotBlank)
    val description = (visibleSegments + listOfNotNull(failureText?.takeIf(String::isNotBlank))).joinToString(stringResource(R.string.chat_step_summary_separator))
    val rotation by animateFloatAsState(
        targetValue = if (expanded == true) 90f else 0f,
        animationSpec = tween(150),
        label = "stepExpansion",
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
            .semantics(mergeDescendants = true) {
                if (tag != null) testTag = tag
                contentDescription = description
                stateDescription = state
                if (active) {
                    progressBarRangeInfo = progress?.let { ProgressBarRangeInfo(it, 0f..1f) }
                        ?: ProgressBarRangeInfo.Indeterminate
                }
            },
    ) {
        val summaryMaxWidth = (maxWidth - 58.dp).coerceAtLeast(0.dp)
        Row(Modifier.fillMaxWidth().align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            val summaryText = visibleSegments.joinToString(" $NexaraMetaSeparator ")
            if (preview == null) {
                // 摘要按内容宽度排布并先截断，失败段紧跟其后且不收缩，箭头固定在行尾。
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = summaryText,
                        style = style,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).stepShimmer(active).testTag("CHAT_STEP_SUMMARY"),
                    )
                    if (!failureText.isNullOrBlank()) {
                        Text(
                            text = " $NexaraMetaSeparator $failureText",
                            style = style,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag("CHAT_STEP_FAILURE"),
                        )
                    }
                }
            } else {
                Text(
                    text = summaryText,
                    style = style,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = summaryMaxWidth).stepShimmer(active).testTag("CHAT_STEP_SUMMARY"),
                )
            }
            if (preview != null) {
                Spacer(Modifier.width(12.dp))
                preview.invoke(this)
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(16.dp).rotate(rotation).testTag("CHAT_STEP_ARROW"),
            )
        }
    }
}
