package com.promenar.nexara.util

import android.content.Context
object LocaleHelper {
    fun applyLanguage(context: Context, languageCode: String): Context =
        LocaleController.wrap(context, languageCode)

    fun getSavedLanguage(context: Context): String = LocaleController.getSavedLanguage(context)

    fun saveLanguage(context: Context, languageCode: String): Boolean =
        LocaleController.setApplicationLanguage(context, languageCode)
}
