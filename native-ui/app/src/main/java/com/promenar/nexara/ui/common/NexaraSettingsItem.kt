package com.promenar.nexara.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.theme.NexaraSpacing

private val SettingsLeadingColumnWidth = 48.dp
private val SettingsChevronColumnWidth = 32.dp

/**
 * 可复用的 Material 3 设置行。
 *
 * 文本区域保持可伸缩，允许长标题和副标题自然换行；整行维持统一的按钮语义与
 * 最小触控尺寸，供 Hub、Settings 与列表页面复用。
 */
@Composable
fun NexaraSettingsItem(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    onClickLabel: String? = null,
    titleTextStyle: TextStyle? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(
                minHeight = if (subtitle == null) 56.dp else 72.dp,
            )
            .clickable(
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick,
            )
            .padding(vertical = NexaraSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier.width(SettingsLeadingColumnWidth),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = titleTextStyle ?: MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(NexaraSpacing.Small))
            Box(
                modifier = Modifier.sizeIn(
                    minWidth = NexaraSpacing.MinimumTouchTarget,
                    minHeight = NexaraSpacing.MinimumTouchTarget,
                ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                trailingContent()
            }
        } else if (showChevron) {
            Spacer(modifier = Modifier.width(NexaraSpacing.Small))
            Box(
                modifier = Modifier
                    .width(SettingsChevronColumnWidth)
                    .defaultMinSize(minHeight = NexaraSpacing.MinimumTouchTarget),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
