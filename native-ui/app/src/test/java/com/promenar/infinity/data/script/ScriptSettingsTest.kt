package com.promenar.infinity.data.script

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.script.ScriptFailure
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScriptSettingsTest {
    @Test fun `网络白名单持久化且不依赖旧脚本写入审批设置`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("script-settings-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = ScriptSettings(prefs)
        assertThat(settings.lanHosts).isEmpty()
        prefs.edit().putBoolean("script_approve_writes", true).commit()
        settings.lanHostsText = "LAN.test\n192.168.1.5"
        val recreated = ScriptSettings(prefs)
        assertThat(recreated.lanHosts).containsExactly("lan.test", "192.168.1.5")
        assertThat(prefs.getBoolean("script_approve_writes", false)).isTrue()
        assertThrows(ScriptFailure::class.java) { recreated.lanHostsText = "*.internal" }
        assertThat(recreated.lanHosts).containsExactly("lan.test", "192.168.1.5")
    }
}
