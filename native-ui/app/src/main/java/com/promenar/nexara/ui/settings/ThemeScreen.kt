package com.promenar.nexara.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.LocalNexaraHaptics
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraNumericSetting
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraValueRow
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraPresetColors
import com.promenar.nexara.ui.theme.NexaraThemeMode
import kotlin.math.roundToInt

@Composable
fun ThemeScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current.applicationContext as NexaraApplication
    val viewModel: ThemeViewModel = viewModel(
        factory = ThemeViewModel.provideFactory(context.themePreferenceStore),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ThemeScreenContent(
        state = state,
        onModeSelect = viewModel::setThemeMode,
        onColorSourceSelect = viewModel::setColorSource,
        onNavigateBack = onNavigateBack,
        onPrimaryColorSelect = viewModel::setPrimaryColor,
        onPureBlackChange = viewModel::setPureBlack,
        onTextScaleEnabledChange = viewModel::setTextScaleEnabled,
        onTextScaleChange = viewModel::setTextScale,
        onCollapseFinishedTurnsChange = viewModel::setCollapseFinishedTurns,
        onHapticsChange = viewModel::setHapticsEnabled,
        onStatusHapticsChange = viewModel::setStatusHapticsEnabled,
    )
}

@Composable
fun ThemeScreenContent(
    state: ThemeUiState,
    onModeSelect: (NexaraThemeMode) -> Unit,
    onColorSourceSelect: (NexaraColorSource) -> Unit,
    onNavigateBack: () -> Unit,
    onPrimaryColorSelect: (Long?) -> Unit = {},
    onPureBlackChange: (Boolean) -> Unit = {},
    onTextScaleEnabledChange: (Boolean) -> Unit = {},
    onTextScaleChange: (Float) -> Unit = {},
    onCollapseFinishedTurnsChange: (Boolean) -> Unit = {},
    onHapticsChange: (Boolean) -> Unit = {},
    onStatusHapticsChange: (Boolean) -> Unit = {},
) {
    val preferences = state.preferences
    val dynamicColorChecked = state.dynamicColorAvailable &&
        preferences.colorSource == NexaraColorSource.DYNAMIC

    NexaraSettingsPageLayout(
        title = stringResource(R.string.theme_title),
        onBack = onNavigateBack,
    ) { contentPadding ->
        LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = contentPadding) {
            item("mode") {
                NexaraSettingsSection(title = stringResource(R.string.settings_batch1_display_style)) {
                    ThemeModeSelector(
                        selectedMode = preferences.mode,
                        onModeSelect = onModeSelect,
                    )
                }
            }

            item("color") {
                NexaraSettingsSection(
                    title = stringResource(R.string.theme_accent_color),
                    footer = if (state.dynamicColorAvailable) stringResource(R.string.theme_dynamic_color_desc) else null,
                ) {
                    ColorPaletteRow(
                        selectedColor = preferences.primaryColor,
                        onColorSelect = onPrimaryColorSelect,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                    SettingsToggle(
                        title = stringResource(R.string.theme_dynamic_color),
                        description = stringResource(
                            if (state.dynamicColorAvailable) R.string.settings_batch1_dynamic_color_summary
                            else R.string.settings_batch1_dynamic_color_unavailable_summary,
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

            if (preferences.mode == NexaraThemeMode.DARK) {
                item("pure-black") {
                    NexaraSettingsSection(
                        title = stringResource(R.string.theme_dark_mode),
                    ) {
                        SettingsToggle(
                            title = stringResource(R.string.theme_pure_black),
                            description = stringResource(R.string.settings_batch1_pure_black_summary),
                            checked = preferences.pureBlack,
                            onCheckedChange = onPureBlackChange,
                        )
                    }
                }
            }

            item("text-scale") {
                NexaraSettingsSection(
                    title = stringResource(R.string.theme_text),
                ) {
                    SettingsToggle(
                        title = stringResource(R.string.theme_text_scale),
                        description = stringResource(R.string.settings_batch1_text_scale_summary),
                        checked = preferences.textScaleEnabled,
                        onCheckedChange = onTextScaleEnabledChange,
                    )
                    if (preferences.textScaleEnabled) {
                        NexaraNumericSetting(
                            title = stringResource(R.string.settings_batch1_text_scale_value),
                            value = (preferences.textScale * 100).roundToInt(),
                            spec = InterfaceTextScaleSpec,
                            description = stringResource(R.string.theme_text_scale_desc),
                            onValueChange = { onTextScaleChange((it / 100.0).toFloat()) },
                        )
                    }
                }
            }
            item("chat") {
                NexaraSettingsSection(
                    title = stringResource(R.string.turn_collapse_settings_section),
                ) {
                    SettingsToggle(
                        title = stringResource(R.string.turn_collapse_setting_title),
                        description = stringResource(R.string.turn_collapse_setting_summary),
                        checked = preferences.collapseFinishedTurns,
                        onCheckedChange = onCollapseFinishedTurnsChange,
                        modifier = Modifier.testTag("theme_collapse_finished_turns_switch"),
                    )
                }
            }
            item("haptics") {
                NexaraSettingsSection(
                    title = stringResource(R.string.haptics_settings_section),
                    footer = stringResource(R.string.haptics_settings_footer),
                ) {
                    val haptics = LocalNexaraHaptics.current
                    SettingsToggle(
                        title = stringResource(R.string.haptics_setting_title),
                        description = stringResource(R.string.haptics_setting_summary),
                        checked = preferences.hapticsEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) haptics.confirmEnabled()
                            onHapticsChange(enabled)
                        },
                        modifier = Modifier.testTag("theme_haptics_switch"),
                    )
                    SettingsToggle(
                        title = stringResource(R.string.haptics_status_setting_title),
                        description = stringResource(R.string.haptics_status_setting_summary),
                        checked = preferences.hapticsEnabled && preferences.statusHapticsEnabled,
                        enabled = preferences.hapticsEnabled,
                        onCheckedChange = onStatusHapticsChange,
                        modifier = Modifier.testTag("theme_status_haptics_switch"),
                    )
                }
            }
            item("bottom-space") { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ThemeModeSelector(
    selectedMode: NexaraThemeMode,
    onModeSelect: (NexaraThemeMode) -> Unit,
) {
    var showModes by remember { mutableStateOf(false) }
    val modes = listOf(
        Triple(NexaraThemeMode.SYSTEM, Icons.Rounded.BrightnessAuto, R.string.settings_theme_system),
        Triple(NexaraThemeMode.LIGHT, Icons.Rounded.LightMode, R.string.settings_theme_light),
        Triple(NexaraThemeMode.DARK, Icons.Rounded.DarkMode, R.string.settings_theme_dark),
    )
    NexaraValueRow(
        title = stringResource(R.string.theme_mode),
        value = stringResource(modes.first { it.first == selectedMode }.third),
        modifier = Modifier.testTag("theme_mode_picker"),
        onClick = { showModes = true },
    )
    NexaraBottomSheet(show = showModes, title = stringResource(R.string.theme_mode),
        onDismiss = { showModes = false }, scrollContent = true) {
        modes.forEach { (mode, _, titleRes) ->
            val selected = selectedMode == mode
            NexaraSettingsItem(
                title = stringResource(titleRes),
                horizontalPadding = 0.dp,
                onClick = { onModeSelect(mode); showModes = false },
                modifier = Modifier.testTag("theme_mode_${mode.name.lowercase()}"),
                role = Role.RadioButton,
                selected = selected,
                trailingContent = { androidx.compose.material3.RadioButton(selected = selected, onClick = null) },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ColorPaletteRow(
    selectedColor: Long?,
    onColorSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 8 个色块排成两行各 4 个并均分宽度，避免窄屏上第二行只剩一个孤立色块。
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround,
        maxItemsInEachRow = 4,
    ) {
        ColorChoice(
            color = MaterialTheme.colorScheme.primary,
            selected = selectedColor == null,
            onClick = { onColorSelect(null) },
        )
        NexaraPresetColors.take(7).forEach { color ->
            ColorChoice(
                color = color,
                selected = selectedColor == color.value.toLong(),
                onClick = { onColorSelect(color.value.toLong()) },
            )
        }
    }
}

@Composable
private fun ColorChoice(color: Color, selected: Boolean, onClick: () -> Unit) {
    val colorLabel = stringResource(R.string.settings_batch1_color_description, "%06X".format(color.toArgb() and 0xFFFFFF))
    val haptics = LocalNexaraHaptics.current
    Box(
        modifier = Modifier.size(48.dp).semantics { contentDescription = colorLabel }
            .selectable(selected = selected, role = Role.RadioButton, onClick = { haptics.select(selected); onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(CircleShape).background(color)
                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(18.dp))
            }
        }
    }
}
