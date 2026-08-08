package com.promenar.nexara.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.theme.NexaraSpacing
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

    NexaraSettingsPageLayout(
        title = stringResource(R.string.theme_title),
        onBack = onNavigateBack
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = contentPadding,
        ) {
            item("description") {
                Text(
                    text = stringResource(R.string.theme_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = NexaraSpacing.XSmall,
                        vertical = NexaraSpacing.Small,
                    ),
                )
            }
            item("mode") {
                NexaraSettingsSection(title = stringResource(R.string.theme_mode)) {
                    ThemeModeRow(
                        title = stringResource(R.string.settings_theme_system),
                        selected = state.preferences.mode == NexaraThemeMode.SYSTEM,
                        onClick = { onModeSelect(NexaraThemeMode.SYSTEM) },
                        modifier = Modifier.testTag("theme_mode_system"),
                    )
                    ThemeModeRow(
                        title = stringResource(R.string.settings_theme_light),
                        selected = state.preferences.mode == NexaraThemeMode.LIGHT,
                        onClick = { onModeSelect(NexaraThemeMode.LIGHT) },
                        modifier = Modifier.testTag("theme_mode_light"),
                    )
                    ThemeModeRow(
                        title = stringResource(R.string.settings_theme_dark),
                        selected = state.preferences.mode == NexaraThemeMode.DARK,
                        onClick = { onModeSelect(NexaraThemeMode.DARK) },
                        modifier = Modifier.testTag("theme_mode_dark"),
                    )
                }
            }
            item("color") {
                NexaraSettingsSection(title = stringResource(R.string.settings_theme_color)) {
                    SettingsToggle(
                        title = stringResource(R.string.theme_dynamic_color),
                        description = stringResource(
                            if (state.dynamicColorAvailable) {
                                R.string.theme_dynamic_color_desc
                            } else {
                                R.string.theme_dynamic_color_unavailable
                            },
                        ),
                        checked = dynamicColorChecked,
                        enabled = state.dynamicColorAvailable,
                        onCheckedChange = { enabled ->
                            onColorSourceSelect(
                                if (enabled) NexaraColorSource.DYNAMIC else NexaraColorSource.NEXARA,
                            )
                        },
                        modifier = Modifier.testTag("theme_dynamic_color_switch"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeModeRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = NexaraSpacing.MinimumTouchTarget)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = NexaraSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        RadioButton(
            selected = selected,
            onClick = null,
        )
    }
}
