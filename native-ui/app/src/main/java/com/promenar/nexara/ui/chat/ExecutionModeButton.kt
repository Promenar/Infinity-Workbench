package com.promenar.nexara.ui.chat

import com.promenar.nexara.ui.common.NexaraHapticEvent
import com.promenar.nexara.ui.common.LocalNexaraHaptics
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
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.ExecutionMode
import com.promenar.nexara.ui.common.NexaraDropdownMenu
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.common.NexaraMenuItem

/** 浮岛工具行上的执行模式切换：只显示盾牌图标，完全访问时换成警示图标与第三色，点按弹出两种模式。 */
@Composable
internal fun ExecutionModeButton(
    mode: ExecutionMode,
    onSelect: (ExecutionMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.chat_execution_mode_cd, stringResource(mode.labelRes))
    val haptics = LocalNexaraHaptics.current
    Box(modifier) {
        ChatCompactIconButton(
            onClick = { haptics.perform(NexaraHapticEvent.SELECTION); expanded = true },
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
        NexaraDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ExecutionMode.entries.forEach { option ->
                NexaraMenuItem(
                    text = {
                        // 浮层只放标题和一行短说明，完整解释在“Agent 可达范围”面板。
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(option.labelRes), style = NexaraTypography.labelLarge, maxLines = 1)
                            Text(
                                stringResource(option.hintRes()),
                                style = NexaraTypography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    },
                    leadingIcon = { Icon(option.icon(), contentDescription = null, tint = option.tint()) },
                    trailingIcon = if (option == mode) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    haptic = NexaraHapticEvent.SELECTION.takeIf { option != mode },
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
