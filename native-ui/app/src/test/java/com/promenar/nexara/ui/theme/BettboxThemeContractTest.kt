package com.promenar.nexara.ui.theme

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class BettboxThemeContractTest {
    private fun source(fileName: String): String = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/nexara/ui/theme/$fileName"),
    ).toString(Charsets.UTF_8)

    @Test
    fun `外观偏好应持久化主题种子色纯黑和全局字号`() {
        val preferences = source("NexaraThemePreferences.kt")
        val store = source("ThemePreferenceStore.kt")

        assertThat(preferences).contains("val primaryColor: Long?")
        assertThat(preferences).contains("val pureBlack: Boolean")
        assertThat(preferences).contains("val textScaleEnabled: Boolean")
        assertThat(preferences).contains("val textScale: Float")
        assertThat(store).contains("fun setPrimaryColor(")
        assertThat(store).contains("fun setPureBlack(")
        assertThat(store).contains("fun setTextScale(")
    }

    @Test
    fun `应用主题应消费种子色纯黑和全局字号`() {
        val theme = source("Theme.kt")

        assertThat(theme).contains("seededColorScheme(")
        assertThat(theme).contains("preferences?.pureBlack")
        assertThat(theme).contains("preferences?.textScaleEnabled")
        assertThat(theme).contains("LocalDensity")
    }
}
