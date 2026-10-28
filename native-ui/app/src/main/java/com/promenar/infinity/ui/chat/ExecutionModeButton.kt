package com.promenar.infinity.ui.chat

import com.promenar.infinity.ui.common.InfinityHapticEvent
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.ExecutionMode
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.common.InfinityMenuItem

/** 浮岛工具行上的执行模式切换：只显示盾牌图标，完全访问时换成警示图标与第三色，点按弹出两种模式。 */
@Composable
internal fun ExecutionModeButton(
    mode: ExecutionMode,
    onSelect: (ExecutionMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.chat_execution_mode_cd, stringResource(mode.labelRes))
    val haptics = LocalInfinityHaptics.current
    Box(modifier) {
        ChatCompactIconButton(
            onClick = { haptics.perform(InfinityHapticEvent.SELECTION); expanded = true },
            modifier = Modifier
                .semantics { contentDescription = description }
                .testTag(UiTags.CHAT_EXECUTION_MODE),
        ) {
            Icon(
                imageVector = mode.icon(),
                contentDescription = null,
                tint = mode.tint(),
                modifier = Modifier.size(20.dp),
            )
        }
        InfinityDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ExecutionMode.entries.forEach { option ->
                InfinityMenuItem(
                    text = {
                        // 浮层只放标题和一行短说明，完整解释在“Agent 可达范围”面板。
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(option.labelRes), style = InfinityTypography.labelLarge, maxLines = 1)
                            Text(
                                stringResource(option.hintRes()),
                                style = InfinityTypography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    },
                    leadingIcon = { Icon(option.icon(), contentDescription = null, tint = option.tint()) },
                    trailingIcon = if (option == mode) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    haptic = InfinityHapticEvent.SELECTION.takeIf { option != mode },
                    onClick = {
                        expanded = false
                        if (option != mode) onSelect(option)
                    },
                    modifier = Modifier.testTag("${UiTags.CHAT_EXECUTION_MODE}_${option.name.lowercase()}"),
                )
            }
        }
    }
}

private fun ExecutionMode.hintRes(): Int = when (this) {
    ExecutionMode.WORKSPACE -> R.string.chat_execution_mode_workspace_hint
    ExecutionMode.FULL_ACCESS -> R.string.chat_execution_mode_full_access_hint
}

private fun ExecutionMode.icon(): ImageVector = when (this) {
    ExecutionMode.WORKSPACE -> Icons.Outlined.GppGood
    ExecutionMode.FULL_ACCESS -> Icons.Rounded.GppMaybe
}

@Composable
private fun ExecutionMode.tint() = when (this) {
    ExecutionMode.WORKSPACE -> MaterialTheme.colorScheme.onSurfaceVariant
    ExecutionMode.FULL_ACCESS -> MaterialTheme.colorScheme.tertiary
}
