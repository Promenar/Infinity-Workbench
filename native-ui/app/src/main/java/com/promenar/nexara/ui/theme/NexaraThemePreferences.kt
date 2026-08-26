package com.promenar.nexara.ui.theme

enum class NexaraThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        internal fun fromPreferenceValue(value: String?): NexaraThemeMode =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DARK
    }
}

enum class NexaraColorSource {
    NEXARA,
    DYNAMIC;

    companion object {
        internal fun fromPreferenceValue(value: String?): NexaraColorSource =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: NEXARA
    }
}

data class NexaraThemePreferences(
    val mode: NexaraThemeMode = NexaraThemeMode.DARK,
    val colorSource: NexaraColorSource = NexaraColorSource.NEXARA,
    val primaryColor: Long? = null,
    val pureBlack: Boolean = false,
    val textScaleEnabled: Boolean = false,
    val textScale: Float = 1f,
)
