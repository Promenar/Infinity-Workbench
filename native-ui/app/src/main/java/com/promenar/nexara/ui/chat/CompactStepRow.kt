package com.promenar.nexara.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.ui.common.NexaraMetaLine
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography

/** 气泡内步骤的唯一外部间距；组件内部仅负责内容与点击区域。 */
internal val ChatStepSpacing = 6.dp

/** 渐隐只作用于推理预览，不叠加不透明遮罩，适配浅色与深色主题。 */
@Composable
internal fun Modifier.fadingReasoningTail(): Modifier {
    val mask = MaterialTheme.colorScheme.onSurface.copy(alpha = 1f)
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val edge = (16.dp.toPx() / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        drawRect(
            brush = Brush.horizontalGradient(0f to mask, (1f - edge) to mask, 1f to mask.copy(alpha = 0f)),
            blendMode = BlendMode.DstIn,
        )
    }
}

/** 32dp 可见行放在至少 48dp 的点击区域内，三类步骤共享字号、底色与状态布局。 */
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
) {
    val style = NexaraTypography.labelSmall.copy(
        fontSize = (fontSize - 1).coerceAtLeast(11).sp,
        lineHeight = (fontSize + 4).coerceAtLeast(16).sp,
    )
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
            .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
            .clearAndSetSemantics {
                if (tag != null) testTag = tag
                role = Role.Button
                contentDescription = segments.filter(String::isNotBlank).joinToString("，")
                stateDescription = state
                onClick(label = clickLabel) { onClick(); true }
                if (active) {
                    progressBarRangeInfo = progress?.let { ProgressBarRangeInfo(it, 0f..1f) }
                        ?: ProgressBarRangeInfo.Indeterminate
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.38f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (active) {
                if (progress == null) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = tone)
                } else {
                    CircularProgressIndicator(
                        progress = { progress }, modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = tone,
                    )
                }
            } else {
                Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(14.dp))
            }
            Box(
                modifier = Modifier.weight(if (preview == null) 1f else 0.48f)
                    .height(lineHeight)
                    .clipToBounds()
                    .fadingReasoningTail(),
            ) {
                NexaraMetaLine(
                    segments = segments,
                    style = style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                )
            }
            preview?.invoke(this)
            Icon(
                imageVector = when (expanded) {
                    true -> Icons.Rounded.ExpandLess
                    false -> Icons.Rounded.ExpandMore
                    null -> Icons.Rounded.ChevronRight
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
