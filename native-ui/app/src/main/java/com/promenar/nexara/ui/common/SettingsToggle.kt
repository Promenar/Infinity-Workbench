package com.promenar.nexara.ui.common

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
) {
    NexaraSettingsRowLayout(
        title = title, subtitle = description, icon = icon, enabled = enabled, leadingSpacer = leadingSpacer,
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = switchModifier)
    }
}
