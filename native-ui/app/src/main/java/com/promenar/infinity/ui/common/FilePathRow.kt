package com.promenar.infinity.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 路径行的起点片段，例如 Linux 的 `~` 与 `/`、工作区的主页图标。 */
data class FilePathRoot(
    val label: String? = null,
    val icon: ImageVector? = null,
    val contentDescription: String,
    /** 当前路径以它为起点时高亮。 */
    val selected: Boolean,
    val onClick: () -> Unit,
    val tag: String? = null,
)

/**
 * 文件管理统一路径行：起点片段在前，其后是相对起点的各级目录。
 *
 * 末级为当前目录，不可点；路径过长时横向滚动并停在末尾。[trailing] 放排序等次要操作。
 */
@Composable
fun FilePathRow(
    roots: List<FilePathRoot>,
    trail: List<FolderCrumb>,
    onNavigateToCrumb: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    crumbStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val haptics = LocalInfinityHaptics.current
    LaunchedEffect(trail.size, trail.lastOrNull()?.id) { scrollState.animateScrollTo(scrollState.maxValue) }
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = InfinitySpacing.MinimumTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (roots.isNotEmpty()) FilePathRootSegments(roots, enabled)
            trail.forEachIndexed { index, crumb ->
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                val current = index == trail.lastIndex
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (!current && enabled) Modifier.clickable(interactionSource = null, indication = null, role = Role.Button) {
                                haptics.perform(InfinityHapticEvent.SELECTION)
                                onNavigateToCrumb(index)
                            }
                            else Modifier,
                        )
                        .padding(horizontal = InfinitySpacing.Small),
                ) {
                    Text(
                        text = crumb.name,
                        style = crumbStyle,
                        color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        fontWeight = if (current) FontWeight.SemiBold else null,
                        maxLines = 1,
                    )
                }
            }
        }
        trailing()
    }
}

/**
 * 多个起点合成一枚分段胶囊（如 `~ | /`），当前起点实底，读作“从这里出发”；
 * 胶囊视觉高 32dp，每段触控区仍为 48dp。
 */
@Composable
private fun FilePathRootSegments(roots: List<FilePathRoot>, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalInfinityHaptics.current
    Box(contentAlignment = Alignment.Center) {
        // 描边只占中间 32dp，外层每段仍有 48dp 触控区。
        if (roots.size > 1) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(vertical = 8.dp)
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp)),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            roots.forEach { root ->
                // 48dp 触控区大于 32dp 的可见胶囊，水波纹会溢出胶囊；改由选中底色过渡与触感反馈回应点击。
                val fill by animateColorAsState(
                    if (root.selected) colors.secondaryContainer else Color.Transparent,
                    tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate), label = "pathRootFill",
                )
                val tint by animateColorAsState(
                    if (root.selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate), label = "pathRootTint",
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                        .widthIn(min = InfinitySpacing.MinimumTouchTarget)
                        .clickable(interactionSource = null, indication = null, enabled = enabled, role = Role.Button) {
                            haptics.select(root.selected)
                            root.onClick()
                        }
                        .semantics {
                            contentDescription = root.contentDescription
                            selected = root.selected
                        }
                        .then(root.tag?.let { Modifier.testTag(it) } ?: Modifier),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .heightIn(min = 32.dp)
                            .widthIn(min = 44.dp)
                            .background(fill, RoundedCornerShape(16.dp))
                            .padding(horizontal = InfinitySpacing.Medium),
                    ) {
                        when {
                            root.icon != null -> Icon(root.icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
                            else -> Text(
                                text = root.label.orEmpty(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = tint,
                            )
                        }
                    }
                }
            }
        }
    }
}
