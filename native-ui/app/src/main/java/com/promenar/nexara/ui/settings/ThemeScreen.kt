package com.promenar.nexara.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraThemeMode

@Composable
fun ThemeScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current.applicationContext as NexaraApplication
    val store = context.themePreferenceStore
    val viewModel: ThemeViewModel = viewModel(factory = ThemeViewModel.provideFactory(store))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ThemeScreenContent(
        state = state,
        onModeSelect = viewModel::setThemeMode,
        onColorSourceSelect = viewModel::setColorSource,
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun ThemeScreenContent(
    state: ThemeUiState,
    onModeSelect: (NexaraThemeMode) -> Unit,
    onColorSourceSelect: (NexaraColorSource) -> Unit,
    onNavigateBack: () -> Unit
) {
    val dynamicColorChecked = state.dynamicColorAvailable &&
        state.preferences.colorSource == NexaraColorSource.DYNAMIC

    NexaraPageLayout(
        title = stringResource(R.string.theme_title),
        onBack = onNavigateBack
    ) {
        Text(
            text = stringResource(R.string.theme_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.theme_mode),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 跟随系统
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_theme_system)) },
            leadingContent = {
                RadioButton(
                    selected = state.preferences.mode == NexaraThemeMode.SYSTEM,
                    onClick = null
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("theme_mode_system")
                .selectable(
                    selected = state.preferences.mode == NexaraThemeMode.SYSTEM,
                    role = Role.RadioButton,
                    onClick = { onModeSelect(NexaraThemeMode.SYSTEM) },
                )
        )

        // 浅色
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_theme_light)) },
            leadingContent = {
                RadioButton(
                    selected = state.preferences.mode == NexaraThemeMode.LIGHT,
                    onClick = null
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("theme_mode_light")
                .selectable(
                    selected = state.preferences.mode == NexaraThemeMode.LIGHT,
                    role = Role.RadioButton,
                    onClick = { onModeSelect(NexaraThemeMode.LIGHT) },
                )
        )

        // 深色
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_theme_dark)) },
            leadingContent = {
                RadioButton(
                    selected = state.preferences.mode == NexaraThemeMode.DARK,
                    onClick = null
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("theme_mode_dark")
                .selectable(
                    selected = state.preferences.mode == NexaraThemeMode.DARK,
                    role = Role.RadioButton,
                    onClick = { onModeSelect(NexaraThemeMode.DARK) },
                )
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.settings_theme_color),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 动态取色 Switch
        ListItem(
            headlineContent = { Text(stringResource(R.string.theme_dynamic_color)) },
            supportingContent = {
                if (state.dynamicColorAvailable) {
                    Text(stringResource(R.string.theme_dynamic_color_desc))
                } else {
                    Text(stringResource(R.string.theme_dynamic_color_unavailable))
                }
            },
            trailingContent = {
                Switch(
                    checked = dynamicColorChecked,
                    onCheckedChange = null,
                    enabled = state.dynamicColorAvailable
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("theme_dynamic_color_switch")
                .toggleable(
                    value = dynamicColorChecked,
                    enabled = state.dynamicColorAvailable,
                    role = Role.Switch,
                    onValueChange = { enabled ->
                        onColorSourceSelect(
                            if (enabled) NexaraColorSource.DYNAMIC else NexaraColorSource.NEXARA,
                        )
                    },
                )
        )
    }
}
