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
        const val PRIMARY_COLOR_KEY = "theme_primary_color"
        const val PURE_BLACK_KEY = "theme_pure_black"
        const val TEXT_SCALE_ENABLED_KEY = "theme_text_scale_enabled"
        const val TEXT_SCALE_KEY = "theme_text_scale"
        private const val PREFERENCES_NAME = "nexara_settings"
        private val THEME_KEYS = setOf(
            MODE_KEY,
            COLOR_SOURCE_KEY,
            PRIMARY_COLOR_KEY,
            PURE_BLACK_KEY,
            TEXT_SCALE_ENABLED_KEY,
            TEXT_SCALE_KEY,
        )
    }

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readPreferences())
    val state: StateFlow<NexaraThemePreferences> = _state.asStateFlow()

    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in THEME_KEYS) {
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

    fun setPrimaryColor(color: Long?) {
        _state.value = _state.value.copy(primaryColor = color)
        preferences.edit().apply {
            if (color == null) remove(PRIMARY_COLOR_KEY) else putLong(PRIMARY_COLOR_KEY, color)
        }.apply()
    }

    fun setPureBlack(enabled: Boolean) {
        _state.value = _state.value.copy(pureBlack = enabled)
        preferences.edit().putBoolean(PURE_BLACK_KEY, enabled).apply()
    }

    fun setTextScale(enabled: Boolean, scale: Float = _state.value.textScale) {
        val boundedScale = scale.coerceIn(0.8f, 1.4f)
        _state.value = _state.value.copy(
            textScaleEnabled = enabled,
            textScale = boundedScale,
        )
        preferences.edit()
            .putBoolean(TEXT_SCALE_ENABLED_KEY, enabled)
            .putFloat(TEXT_SCALE_KEY, boundedScale)
            .apply()
    }

    private fun readPreferences(): NexaraThemePreferences = NexaraThemePreferences(
        mode = NexaraThemeMode.fromPreferenceValue(preferences.getString(MODE_KEY, null)),
        colorSource = NexaraColorSource.fromPreferenceValue(
            preferences.getString(COLOR_SOURCE_KEY, null),
        ),
        primaryColor = if (preferences.contains(PRIMARY_COLOR_KEY)) {
            preferences.getLong(PRIMARY_COLOR_KEY, 0L)
        } else {
            null
        },
        pureBlack = preferences.getBoolean(PURE_BLACK_KEY, false),
        textScaleEnabled = preferences.getBoolean(TEXT_SCALE_ENABLED_KEY, false),
        textScale = if (preferences.contains(TEXT_SCALE_KEY)) {
            preferences.getFloat(TEXT_SCALE_KEY, 1f).coerceIn(0.8f, 1.4f)
        } else {
            1f
        },
    )
}

internal fun NexaraThemeMode.toPreferenceValue(): String = name.lowercase()

internal fun NexaraColorSource.toPreferenceValue(): String = name.lowercase()
