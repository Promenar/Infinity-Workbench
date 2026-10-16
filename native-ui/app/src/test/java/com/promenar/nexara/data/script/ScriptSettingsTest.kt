package com.promenar.nexara.data.script

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.script.ScriptFailure
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScriptSettingsTest {
    @Test fun `默认空白名单和二次审批持久化且拒绝非法白名单`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("script-settings-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = ScriptSettings(prefs)
        assertThat(settings.lanHosts).isEmpty()
        assertThat(settings.approveFileWrites).isTrue()
        settings.lanHostsText = "LAN.test\n192.168.1.5"
        settings.approveFileWrites = false
        val recreated = ScriptSettings(prefs)
        assertThat(recreated.lanHosts).containsExactly("lan.test", "192.168.1.5")
        assertThat(recreated.approveFileWrites).isFalse()
        assertThrows(ScriptFailure::class.java) { recreated.lanHostsText = "*.internal" }
        assertThat(recreated.lanHosts).containsExactly("lan.test", "192.168.1.5")
    }
}
