package com.promenar.nexara.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** 统一驱动 AppCompat 与 Android 13+ per-app locale，并为旧系统提供 Context 包装。 */
object LocaleController {
    private const val PREFERENCES_NAME = "nexara_settings"
    private const val KEY_LANGUAGE = "language"
    private val supported = setOf("en", "zh")

    fun getSavedLanguage(context: Context): String = context
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getString(KEY_LANGUAGE, "zh")
        ?.takeIf(supported::contains)
        ?: "zh"

    fun setApplicationLanguage(context: Context, languageCode: String): Boolean {
        if (!persistLanguage(context, languageCode)) return false
        applyLanguage(context, languageCode)
        return true
    }

    fun persistLanguage(context: Context, languageCode: String): Boolean {
        if (languageCode !in supported) return false
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, languageCode).commit()
    }

    fun applyLanguage(context: Context, languageCode: String) {
        require(languageCode in supported) { "unsupported_language" }
        val tags = languageCode.toLanguageTag()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales = LocaleList.forLanguageTags(tags)
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
            (context as? Activity)?.recreate()
        }
    }

    fun wrap(context: Context, languageCode: String = getSavedLanguage(context)): Context {
        val locale = Locale.forLanguageTag(languageCode.toLanguageTag())
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration).apply {
            // setLocale 在 API 17 起即可稳定覆盖首选 locale；不要再调用
            // Configuration.setLocales(LocaleList)，旧版 Robolectric/Android 运行时可能缺少该签名。
            setLocale(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    private fun String.toLanguageTag(): String = if (this == "zh") "zh-CN" else "en"
}
