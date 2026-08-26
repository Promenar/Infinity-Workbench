package com.promenar.nexara.ui.theme

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ThemePreferenceStoreTest {

    private fun createStore(): ThemePreferenceStore {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        every { editor.apply() } returns Unit
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { getString("theme_mode", any()) } returns null
            every { getString("theme_color_source", any()) } returns null
            every { edit() } returns editor
        }
        return ThemePreferenceStore(contextFor(prefs))
    }

    private fun contextFor(prefs: SharedPreferences): Context = mockk(relaxed = true) {
        every { getSharedPreferences("nexara_settings", Context.MODE_PRIVATE) } returns prefs
    }

    @Test
    fun `缺失偏好默认 dark 与 Nexara 颜色源`() = runTest {
        val store = createStore()
        val state = store.state.value

        assertThat(state).isEqualTo(
            NexaraThemePreferences(
                mode = NexaraThemeMode.DARK,
                colorSource = NexaraColorSource.NEXARA,
            ),
        )
    }

    @Test
    fun `mode 和 colorSource 持久化恢复`() = runTest {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        every { editor.apply() } returns Unit
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { edit() } returns editor
            every { getString("theme_mode", any()) } returns "SYSTEM"
            every { getString("theme_color_source", any()) } returns "DYNAMIC"
        }

        val store = ThemePreferenceStore(contextFor(prefs))
        store.setMode(NexaraThemeMode.SYSTEM)
        store.setColorSource(NexaraColorSource.DYNAMIC)

        assertThat(store.state.value.mode).isEqualTo(NexaraThemeMode.SYSTEM)
        assertThat(store.state.value.colorSource).isEqualTo(NexaraColorSource.DYNAMIC)

        verify { editor.putString("theme_mode", "system") }
        verify { editor.putString("theme_color_source", "dynamic") }
        verify(exactly = 2) { editor.apply() }
    }

    @Test
    fun `Bettbox 外观偏好立即更新并写入持久化存储`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putFloat(any(), any()) } returns editor
        every { editor.apply() } returns Unit
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { contains(any()) } returns false
            every { getString("theme_mode", any()) } returns null
            every { getString("theme_color_source", any()) } returns null
            every { edit() } returns editor
        }
        val store = ThemePreferenceStore(contextFor(prefs))

        store.setPrimaryColor(0xFF6366F1L)
        store.setPureBlack(true)
        store.setTextScale(enabled = true, scale = 1.2f)

        assertThat(store.state.value.primaryColor).isEqualTo(0xFF6366F1L)
        assertThat(store.state.value.pureBlack).isTrue()
        assertThat(store.state.value.textScaleEnabled).isTrue()
        assertThat(store.state.value.textScale).isWithin(0.001f).of(1.2f)
        verify { editor.putLong("theme_primary_color", 0xFF6366F1L) }
        verify { editor.putBoolean("theme_pure_black", true) }
        verify { editor.putBoolean("theme_text_scale_enabled", true) }
        verify { editor.putFloat("theme_text_scale", 1.2f) }
    }

    @Test
    fun `未知持久值回退到默认策略`() = runTest {
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { getString("theme_mode", any()) } returns "unknown"
            every { getString("theme_color_source", any()) } returns "mystery"
        }

        val store = ThemePreferenceStore(contextFor(prefs))

        assertThat(store.state.value.mode).isEqualTo(NexaraThemeMode.DARK)
        assertThat(store.state.value.colorSource).isEqualTo(NexaraColorSource.NEXARA)
    }

    @Test
    fun `外部 restore 变更无需重启直接更新状态`() {
        val changeListenerSlot = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
        val prefs = mockk<SharedPreferences>(relaxed = true)

        every { prefs.registerOnSharedPreferenceChangeListener(capture(changeListenerSlot)) } returns Unit
        every { prefs.getString("theme_mode", any()) } returns "DARK"
        every { prefs.getString("theme_color_source", any()) } returns "NEXARA"

        val store = ThemePreferenceStore(contextFor(prefs))
        assertThat(store.state.value.mode).isEqualTo(NexaraThemeMode.DARK)
        assertThat(store.state.value.colorSource).isEqualTo(NexaraColorSource.NEXARA)

        every { prefs.getString("theme_mode", any()) } returns "LIGHT"
        every { prefs.getString("theme_color_source", any()) } returns "DYNAMIC"
        changeListenerSlot.captured.onSharedPreferenceChanged(prefs, "theme_mode")

        assertThat(store.state.value.mode).isEqualTo(NexaraThemeMode.LIGHT)
        assertThat(store.state.value.colorSource).isEqualTo(NexaraColorSource.DYNAMIC)
    }
}
