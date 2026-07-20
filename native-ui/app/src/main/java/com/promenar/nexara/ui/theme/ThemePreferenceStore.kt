package com.promenar.nexara.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemePreferenceStore(context: Context) {
    companion object {
        const val MODE_KEY = "theme_mode"
        const val COLOR_SOURCE_KEY = "theme_color_source"
        private const val PREFERENCES_NAME = "nexara_settings"
    }

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readPreferences())
    val state: StateFlow<NexaraThemePreferences> = _state.asStateFlow()

    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MODE_KEY || key == COLOR_SOURCE_KEY) {
            _state.value = readPreferences()
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(changeListener)
    }

    fun setMode(mode: NexaraThemeMode) {
        _state.value = _state.value.copy(mode = mode)
        preferences.edit().putString(MODE_KEY, mode.toPreferenceValue()).apply()
    }

    fun setColorSource(source: NexaraColorSource) {
        _state.value = _state.value.copy(colorSource = source)
        preferences.edit().putString(COLOR_SOURCE_KEY, source.toPreferenceValue()).apply()
    }

    private fun readPreferences(): NexaraThemePreferences = NexaraThemePreferences(
        mode = NexaraThemeMode.fromPreferenceValue(preferences.getString(MODE_KEY, null)),
        colorSource = NexaraColorSource.fromPreferenceValue(
            preferences.getString(COLOR_SOURCE_KEY, null),
        ),
    )
}

internal fun NexaraThemeMode.toPreferenceValue(): String = name.lowercase()

internal fun NexaraColorSource.toPreferenceValue(): String = name.lowercase()
