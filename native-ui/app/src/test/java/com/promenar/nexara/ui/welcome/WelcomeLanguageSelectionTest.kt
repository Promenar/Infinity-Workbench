package com.promenar.nexara.ui.welcome

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WelcomeLanguageSelectionTest {
    private lateinit var context: Context

    @Before
    fun reset() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nexara_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nexara_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `English welcome selection is durable before activity recreation`() {
        assertThat(WelcomeLanguageSelection.complete(context, "en")).isTrue()
        assertThat(
            context.getSharedPreferences("nexara_settings", Context.MODE_PRIVATE)
                .getString("language", null)
        ).isEqualTo("en")
        assertThat(
            context.getSharedPreferences("nexara_prefs", Context.MODE_PRIVATE)
                .getBoolean("has_shown_welcome", false)
        ).isTrue()
    }

    @Test
    fun `Chinese welcome selection stores the explicit Chinese locale`() {
        assertThat(WelcomeLanguageSelection.complete(context, "zh")).isTrue()
        assertThat(
            context.getSharedPreferences("nexara_settings", Context.MODE_PRIVATE)
                .getString("language", null)
        ).isEqualTo("zh")
    }
}
