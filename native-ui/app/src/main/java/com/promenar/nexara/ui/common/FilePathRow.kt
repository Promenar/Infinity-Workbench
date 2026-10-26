package com.promenar.nexara.ui.common

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
import com.promenar.nexara.ui.theme.NexaraSpacing

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
    LaunchedEffect(trail.size, trail.lastOrNull()?.id) { scrollState.animateScrollTo(scrollState.maxValue) }
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = NexaraSpacing.MinimumTouchTarget),
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
                        .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (!current && enabled) Modifier.clickable(role = Role.Button) { onNavigateToCrumb(index) }
                            else Modifier,
                        )
                        .padding(horizontal = NexaraSpacing.Small),
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
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                        .widthIn(min = NexaraSpacing.MinimumTouchTarget)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = enabled, role = Role.Button, onClick = root.onClick)
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
                            .background(
                                if (root.selected) colors.secondaryContainer else Color.Transparent,
                                RoundedCornerShape(16.dp),
                            )
                            .padding(horizontal = NexaraSpacing.Medium),
                    ) {
                        val tint = if (root.selected) colors.onSecondaryContainer else colors.onSurfaceVariant
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
