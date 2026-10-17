package com.promenar.nexara.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/** 标题与数值胶囊共用设置行的垂直中心线。 */
@Composable
fun NexaraStepperSetting(
    title: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
    enabled: Boolean = true,
    leadingSpacer: Boolean = false,
) {
    NexaraSettingsRowLayout(title = title, subtitle = description, icon = icon, enabled = enabled,
        leadingSpacer = leadingSpacer, modifier = modifier) {
        NexaraInlineStepper(title, value, onDecrease, onIncrease, canDecrease = canDecrease,
            canIncrease = canIncrease, enabled = enabled)
    }
}
