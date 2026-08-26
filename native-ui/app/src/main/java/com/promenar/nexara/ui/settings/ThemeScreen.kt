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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
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
                NexaraSettingsSection(title = stringResource(R.string.theme_mode)) {
                    ThemeModeSelector(
                        selectedMode = preferences.mode,
                        onModeSelect = onModeSelect,
                    )
                }
            }

            item("color") {
                NexaraSettingsSection(title = stringResource(R.string.theme_accent_color)) {
                    ColorPaletteRow(
                        selectedColor = preferences.primaryColor,
                        onColorSelect = onPrimaryColorSelect,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                    SettingsToggle(
                        title = stringResource(R.string.theme_dynamic_color),
                        description = stringResource(
                            if (state.dynamicColorAvailable) R.string.theme_dynamic_color_desc
                            else R.string.theme_dynamic_color_unavailable,
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
                    NexaraSettingsSection(title = stringResource(R.string.theme_dark_mode)) {
                        SettingsToggle(
                            title = stringResource(R.string.theme_pure_black),
                            description = stringResource(R.string.theme_pure_black_desc),
                            checked = preferences.pureBlack,
                            onCheckedChange = onPureBlackChange,
                        )
                    }
                }
            }

            item("text-scale") {
                NexaraSettingsSection(title = stringResource(R.string.theme_text)) {
                    SettingsToggle(
                        title = stringResource(R.string.theme_text_scale),
                        description = stringResource(R.string.theme_text_scale_desc),
                        checked = preferences.textScaleEnabled,
                        onCheckedChange = onTextScaleEnabledChange,
                    )
                    if (preferences.textScaleEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Slider(
                                value = preferences.textScale,
                                onValueChange = onTextScaleChange,
                                valueRange = 0.8f..1.4f,
                                steps = 5,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${(preferences.textScale * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
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
    val largeTextLayout = LocalDensity.current.fontScale >= 1.5f
    val modes = listOf(
        Triple(NexaraThemeMode.SYSTEM, Icons.Rounded.BrightnessAuto, R.string.settings_theme_system),
        Triple(NexaraThemeMode.LIGHT, Icons.Rounded.LightMode, R.string.settings_theme_light),
        Triple(NexaraThemeMode.DARK, Icons.Rounded.DarkMode, R.string.settings_theme_dark),
    )
    if (largeTextLayout) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            modes.forEach { (mode, icon, titleRes) ->
                ThemeModeCard(
                    title = stringResource(titleRes),
                    icon = icon,
                    selected = selectedMode == mode,
                    onClick = { onModeSelect(mode) },
                    largeTextLayout = true,
                    modifier = Modifier.fillMaxWidth().testTag("theme_mode_${mode.name.lowercase()}"),
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            modes.forEach { (mode, icon, titleRes) ->
                ThemeModeCard(
                    title = stringResource(titleRes),
                    icon = icon,
                    selected = selectedMode == mode,
                    onClick = { onModeSelect(mode) },
                    largeTextLayout = false,
                    modifier = Modifier.weight(1f).testTag("theme_mode_${mode.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun ThemeModeCard(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    largeTextLayout: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.then(
            if (largeTextLayout) Modifier.height(72.dp) else Modifier.aspectRatio(1.08f),
        )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (largeTextLayout) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ColorPaletteRow(
    selectedColor: Long?,
    onColorSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
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
    Box(
        modifier = Modifier.size(34.dp).clip(CircleShape).background(color)
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                else Modifier,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
