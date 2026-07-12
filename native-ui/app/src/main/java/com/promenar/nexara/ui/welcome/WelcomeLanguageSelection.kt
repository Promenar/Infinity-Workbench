package com.promenar.nexara.ui.welcome

import android.content.Context

/** 在 Activity 重建前同步持久化首次语言选择与欢迎页完成状态。 */
object WelcomeLanguageSelection {
    fun complete(context: Context, languageCode: String): Boolean {
        require(languageCode == "en" || languageCode == "zh") { "unsupported_language" }
        val languageSaved = context
            .getSharedPreferences("nexara_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("language", languageCode)
            .commit()
        if (!languageSaved) return false
        return context
            .getSharedPreferences("nexara_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("has_shown_welcome", true)
            .commit()
    }
}
