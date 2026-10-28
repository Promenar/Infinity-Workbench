package com.promenar.nexara.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraSpacing

/**
 * 设置分组内的折叠行：外观与普通设置行一致，点按展开或收起其下的设置行，箭头随之翻转。
 * 展开状态由调用方持有；不需要跨页面保留时用 `remember` 即可，每次进入页面都是收起的。
 */
@Composable
fun ColumnScope.NexaraSettingsExpandableRow(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "settings_expandable_chevron")
    val stateLabel = stringResource(if (expanded) R.string.common_cd_collapse else R.string.common_cd_expand)
    NexaraSettingsRowLayout(
        title = title,
        subtitle = subtitle,
        icon = icon,
        subtitleMaxLines = 2,
        modifier = modifier
            .semantics { stateDescription = stateLabel }
            .toggleable(value = expanded, role = Role.Button, onValueChange = onExpandedChange),
    ) {
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).rotate(rotation),
        )
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = NexaraSpacing.RowHorizontal),
            )
            content()
        }
    }
}

/** 分组色面内的小标题，把一组设置行再分成几段。 */
@Composable
fun NexaraSettingsSubheader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = NexaraSpacing.RowHorizontal, end = NexaraSpacing.RowHorizontal, top = 14.dp, bottom = 6.dp),
    )
}
