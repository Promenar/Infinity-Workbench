package com.promenar.nexara.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class LocaleControllerTest {
    private lateinit var context: Context
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        originalLocale = Locale.getDefault()
        context.getSharedPreferences("nexara_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        context.getSharedPreferences("nexara_settings", Context.MODE_PRIVATE).edit().clear().commit()
        Locale.setDefault(originalLocale)
    }

    @Test
    fun wrap_使用兼容配置切换中文资源环境() {
        val wrapped = LocaleController.wrap(context, "zh")

        @Suppress("DEPRECATION")
        assertEquals("zh", wrapped.resources.configuration.locale.language)
    }

    @Test
    fun setApplicationLanguage_拒绝不支持语言且不污染持久化值() {
        assertFalse(LocaleController.setApplicationLanguage(context, "fr"))
        assertEquals("zh", LocaleController.getSavedLanguage(context))
    }

    @Test
    fun setApplicationLanguage_成功后同步持久化真实语言() {
        assertTrue(LocaleController.setApplicationLanguage(context, "en"))
        assertEquals("en", LocaleController.getSavedLanguage(context))
    }
}
