package com.promenar.infinity.ui.common

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role

@Composable
fun SettingsToggle(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    switchModifier: Modifier = Modifier,
    leadingSpacer: Boolean = false,
    descriptionMaxLines: Int = 1,
) {
    val haptics = LocalInfinityHaptics.current
    InfinitySettingsRowLayout(
        title = title, subtitle = description, icon = icon, enabled = enabled, leadingSpacer = leadingSpacer,
        subtitleMaxLines = descriptionMaxLines,
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = {
            haptics.toggle(it)
            onCheckedChange(it)
        }),
    ) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = switchModifier)
    }
}

/** 独立开关：切换时按开或关给出触感反馈；[onCheckedChange] 为空时由外层行负责切换与反馈。 */
@Composable
fun InfinitySwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalInfinityHaptics.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange?.let { change ->
            { value: Boolean ->
                haptics.toggle(value)
                change(value)
            }
        },
        modifier = modifier,
        enabled = enabled,
    )
}
