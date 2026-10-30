package com.promenar.infinity.data.ssh

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SshTerminalIdlePolicyTest {
    @Test fun `缺省和未知持久值都关闭自动断开`() {
        val settings = mockk<SharedPreferences>()
        listOf(null, "invalid", "off").forEach { value ->
            every { settings.getString(SshTerminalIdlePolicy.KEY, "off") } returns value
            assertThat(SshTerminalIdlePolicy.read(settings)).isEqualTo(SshTerminalIdlePolicy.OFF)
        }
        every { settings.getString(SshTerminalIdlePolicy.KEY, "off") } throws ClassCastException()
        assertThat(SshTerminalIdlePolicy.read(settings)).isEqualTo(SshTerminalIdlePolicy.OFF)
    }

    @Test fun `写入持久值并通过监听流发布策略变化及注销监听`() = runTest {
        val settings = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        var stored: String? = "off"
        var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        every { settings.getString(SshTerminalIdlePolicy.KEY, "off") } answers { stored }
        every { settings.registerOnSharedPreferenceChangeListener(any()) } answers { listener = firstArg(); Unit }
        every { settings.unregisterOnSharedPreferenceChangeListener(any()) } answers { listener = null; Unit }
        every { settings.edit() } returns editor
        every { editor.putString(SshTerminalIdlePolicy.KEY, any()) } answers { stored = secondArg(); editor }
        every { editor.apply() } answers { listener?.onSharedPreferenceChanged(settings, SshTerminalIdlePolicy.KEY); Unit }
        val values = async { SshTerminalIdlePolicy.observe(settings).take(3).toList() }
        runCurrent()
        SshTerminalIdlePolicy.write(settings, SshTerminalIdlePolicy.MINUTES_30)
        runCurrent()
        assertThat(stored).isEqualTo("30m")
        assertThat(SshTerminalIdlePolicy.read(settings).timeoutMillis).isEqualTo(1_800_000L)
        SshTerminalIdlePolicy.write(settings, SshTerminalIdlePolicy.HOURS_2)
        assertThat(values.await()).containsExactly(SshTerminalIdlePolicy.OFF, SshTerminalIdlePolicy.MINUTES_30, SshTerminalIdlePolicy.HOURS_2).inOrder()
        assertThat(stored).isEqualTo("2h")
        assertThat(SshTerminalIdlePolicy.read(settings).timeoutMillis).isEqualTo(7_200_000L)
        assertThat(listener).isNull()
    }
}
