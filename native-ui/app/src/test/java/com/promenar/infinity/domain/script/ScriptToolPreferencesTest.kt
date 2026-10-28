package com.promenar.infinity.domain.script

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.BuiltinToolPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class ScriptToolPreferencesTest {
    @Test fun `旧启用集合不屏蔽新增脚本工具而显式关闭会保留`() {
        val preferences = mockk<SharedPreferences>()
        every { preferences.getStringSet(BuiltinToolPreferences.ENABLED_KEY, null) } returns setOf("calculator")
        every { preferences.getStringSet(BuiltinToolPreferences.KNOWN_KEY, null) } returns null
        assertThat(BuiltinToolPreferences.isEnabled(preferences, "run_script")).isTrue()
        every { preferences.getStringSet(BuiltinToolPreferences.KNOWN_KEY, null) } returns setOf("calculator", "run_script")
        assertThat(BuiltinToolPreferences.isEnabled(preferences, "run_script")).isFalse()
    }
}
