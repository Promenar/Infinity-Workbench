package com.promenar.nexara.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.breakableIdentifier

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
    subtitleIsIdentifier: Boolean = false,
    enabled: Boolean = true,
    leadingSpacer: Boolean = false,
    titleColor: Color? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    subtitleModifier: Modifier = Modifier,
    role: Role = Role.Button,
    selected: Boolean? = null,
    horizontalPadding: Dp = NexaraSpacing.RowHorizontal,
) {
    NexaraSettingsRowLayout(
        title = title,
        subtitle = subtitle,
        icon = icon,
        modifier = modifier.semantics { if (selected != null) this.selected = selected }
            .clickable(enabled = enabled, role = role, onClickLabel = onClickLabel, onClick = onClick),
        enabled = enabled,
        leadingSpacer = leadingSpacer,
        titleTextStyle = titleTextStyle,
        titleColor = titleColor,
        subtitleIsIdentifier = subtitleIsIdentifier,
        leadingContent = leadingContent,
        subtitleModifier = subtitleModifier,
        horizontalPadding = horizontalPadding,
        trailingContent = when {
            trailingContent != null -> trailingContent
            showChevron -> ({ Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant) })
            else -> null
        },
    )
}

/** 所有设置行共享几何布局；交互与状态语义由包装控件提供。 */
@Composable
internal fun NexaraSettingsRowLayout(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    leadingSpacer: Boolean = false,
    titleTextStyle: TextStyle? = null,
    titleColor: Color? = null,
    subtitleIsIdentifier: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingMaxWidthFraction: Float = 1f,
    subtitleModifier: Modifier = Modifier,
    horizontalPadding: Dp = NexaraSpacing.RowHorizontal,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f)) {
        val trailingMaxWidth = maxWidth * trailingMaxWidthFraction
        val expandTitle = maxWidth < 280.dp || LocalDensity.current.fontScale > 1f
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = if (subtitle == null) NexaraSpacing.RowSingleLineHeight else NexaraSpacing.RowTwoLineHeight)
                .padding(horizontal = horizontalPadding, vertical = if (subtitle == null) 0.dp else NexaraSpacing.RowTwoLineVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null || leadingContent != null || leadingSpacer) {
                Box(Modifier.width(NexaraSpacing.RowIconSize + NexaraSpacing.RowIconGap), contentAlignment = Alignment.CenterStart) {
                    if (leadingContent != null) {
                        Box(Modifier.size(NexaraSpacing.RowIconSize), contentAlignment = Alignment.Center) { leadingContent() }
                    } else if (icon != null) {
                        Icon(icon, null, Modifier.size(NexaraSpacing.RowIconSize), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = titleTextStyle ?: MaterialTheme.typography.bodyLarge,
                    color = titleColor ?: MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expandTitle) Int.MAX_VALUE else if (subtitle == null) 2 else 1,
                    overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Spacer(Modifier.height(NexaraSpacing.RowTwoLineGap))
                    Text(if (subtitleIsIdentifier) subtitle.breakableIdentifier() else subtitle,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = subtitleModifier,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailingContent != null) {
                Spacer(Modifier.width(12.dp))
                Box(Modifier.widthIn(max = trailingMaxWidth), contentAlignment = Alignment.Center) { trailingContent() }
            }
        }
    }
}
