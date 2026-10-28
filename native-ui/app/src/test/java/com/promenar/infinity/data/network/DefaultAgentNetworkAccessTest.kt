package com.promenar.infinity.data.network

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.script.ScriptSettings
import com.promenar.infinity.data.tailnet.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DefaultAgentNetworkAccessTest {
    @Test fun `共享设置读取旧键并跟随脚本入口写入及tailnet状态`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("infinity_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("script_lan_hosts", "LAN.test\n127.0.0.1").commit()
        val settings = AgentNetworkSettings(prefs)
        assertThat(settings.lanHosts).containsExactly("127.0.0.1", "lan.test")
        val scripts = ScriptSettings(prefs)
        scripts.lanHostsText = "BEFORE.test"
        val state = MutableStateFlow(TailnetState(TailnetPhase.STOPPED))
        val tailnetSettings = MutableStateFlow(TailnetSettings(enabled = true, allowedPeers = listOf("main")))
        val controller = mockk<TailnetController> {
            every { available } returns true
            every { this@mockk.state } returns state
            every { this@mockk.settings } returns tailnetSettings
        }
        val access = DefaultAgentNetworkAccess(settings, controller, backgroundScope)
        assertThat(access.snapshot.value.lanHosts).containsExactly("before.test")
        assertThat(access.snapshot.value.tailnetEnabled).isTrue()
        assertThat(access.snapshot.value.tailnetAvailable).isTrue()
        assertThat(access.snapshot.value.tailnetRunning).isFalse()
        assertThat(access.snapshot.value.tailnetPeerAllowlist).containsExactly("main")
        runCurrent()
        scripts.lanHostsText = "new.test"
        shadowOf(Looper.getMainLooper()).idle()
        state.value = TailnetState(TailnetPhase.RUNNING)
        tailnetSettings.value = TailnetSettings(enabled = true, allowedPeers = listOf("second", "main"))
        runCurrent()
        assertThat(access.snapshot.value.lanHosts).containsExactly("new.test")
        assertThat(access.snapshot.value.tailnetRunning).isTrue()
        assertThat(access.snapshot.value.tailnetPeerAllowlist).containsExactly("second", "main").inOrder()
        access.setLanHosts("LOCALHOST, [::1]")
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertThat(access.snapshot.value.lanHosts).containsExactly("::1", "localhost").inOrder()
        assertThat(scripts.lanHosts).containsExactly("::1", "localhost")
        val invalid = assertThrows(IllegalArgumentException::class.java) { access.setLanHosts("*.test") }
        assertThat(invalid.message).contains("精确主机")
        assertThat(scripts.lanHosts).containsExactly("::1", "localhost")
        prefs.edit().clear().commit()
    }
}
