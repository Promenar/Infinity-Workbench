package com.promenar.nexara.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.model.SessionHeaderMode
import com.promenar.nexara.data.model.SessionHeaderPolicy
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraPopoverSpec
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraValueRow
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.common.nexaraPopoverAnchor
import com.promenar.nexara.ui.common.rememberNexaraPopoverAnchor
import com.promenar.nexara.ui.theme.forInput

@Composable
internal fun ProviderSessionHeaderFields(enabled: Boolean, name: String, onEnabled: (Boolean) -> Unit, onName: (String) -> Unit) {
    NexaraSettingsSection(title = stringResource(R.string.session_header_title), footer = stringResource(R.string.session_header_provider_help)) {
        SettingsToggle(title = stringResource(R.string.session_header_title), checked = enabled, onCheckedChange = onEnabled)
        if (enabled || name.isNotEmpty() && !SessionHeaderPolicy.isValidName(name)) {
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                textStyle = LocalTextStyle.current.forInput(),
                singleLine = true,
                label = { Text(stringResource(R.string.session_header_name)) },
                isError = !SessionHeaderPolicy.isValidName(name),
                supportingText = if (!SessionHeaderPolicy.isValidName(name)) {{ Text(stringResource(R.string.session_header_name_error)) }} else null,
            )
        }
    }
}

@Composable
internal fun ModelSessionHeaderSetting(mode: SessionHeaderMode, onMode: (SessionHeaderMode) -> Unit) {
    var show by remember { mutableStateOf(false) }
    val anchor = rememberNexaraPopoverAnchor()
    NexaraSettingsSection(title = stringResource(R.string.session_header_title), footer = stringResource(R.string.session_header_model_help)) {
        NexaraValueRow(
            title = stringResource(R.string.session_header_title),
            value = stringResource(mode.labelResource()),
            onClick = { if (!anchor.consumeRecentDismiss()) show = true },
            modifier = Modifier.nexaraPopoverAnchor(anchor),
        )
    }
    NexaraBottomSheet(show = show, title = stringResource(R.string.session_header_title), onDismiss = { show = false }, popover = NexaraPopoverSpec(anchor)) {
        SessionHeaderMode.entries.forEach { option ->
            NexaraSettingsItem(
                title = stringResource(option.labelResource()),
                onClick = { onMode(option); show = false },
                role = Role.RadioButton,
                selected = option == mode,
                trailingContent = { RadioButton(selected = option == mode, onClick = null) },
            )
        }
    }
}

private fun SessionHeaderMode.labelResource(): Int = when (this) {
    SessionHeaderMode.INHERIT -> R.string.session_header_inherit
    SessionHeaderMode.ON -> R.string.session_header_on
    SessionHeaderMode.OFF -> R.string.session_header_off
}
