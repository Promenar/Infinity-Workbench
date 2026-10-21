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
    /** 一轮回复完成后把思考与工具过程收为一行摘要，只单独显示最终正文。 */
    val collapseFinishedTurns: Boolean = true,
    /** 点按、开关、发送与审批等交互的触感反馈；关闭后状态提醒也不振动。 */
    val hapticsEnabled: Boolean = true,
    /** 等待审批、较长生成完成或失败时振动一次。 */
    val statusHapticsEnabled: Boolean = true,
)
