package com.promenar.nexara.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** Bettbox/Material 3 风格的统一设置行。 */
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
    /** 副标题是模型 ID、URL 等标识符时为 true：只在分隔符处折行。 */
    subtitleIsIdentifier: Boolean = false,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = titleTextStyle ?: MaterialTheme.typography.bodyLarge,
            )
        },
        modifier = modifier.alpha(if (enabled) 1f else 0.38f).clickable(
            enabled = enabled,
            role = Role.Button,
            onClickLabel = onClickLabel,
            onClick = onClick,
        ),
        supportingContent = subtitle?.let {
            {
                if (subtitleIsIdentifier) {
                    NexaraIdentifierText(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        leadingContent = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        trailingContent = when {
            trailingContent != null -> trailingContent
            showChevron -> ({
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            })
            else -> null
        },
        colors = ListItemDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}
