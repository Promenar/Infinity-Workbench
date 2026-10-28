package com.promenar.infinity.data.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.SessionHeaderMode
import com.promenar.infinity.data.model.SessionHeaderPolicy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionHeaderPreferencesTest {
    @Test
    fun `删除后的过期模型编辑不能恢复会话请求头模式`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = SessionHeaderPreferences(app.getSharedPreferences("session_header_stale_edit", 0).also { it.edit().clear().commit() })
        val models = mutableSetOf("provider::model")
        val edits = SessionHeaderModelEdits(store) { it in models }
        edits.update("provider::model", SessionHeaderMode.ON) {}
        edits.remove(listOf("provider::model")) { models.clear() }
        var staleMetadataApplied = false
        edits.update("provider::model", SessionHeaderMode.ON) { staleMetadataApplied = true }
        assertThat(store.model("provider::model")).isEqualTo(SessionHeaderMode.INHERIT)
        assertThat(staleMetadataApplied).isFalse()
    }

    @Test
    fun `实际提供商装配重启后保留覆盖且目录刷新不抹掉模式`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        listOf("infinity_provider", "infinity_settings").forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        val secrets = object : com.promenar.infinity.data.security.SecretStore {
            private val values = mutableMapOf<com.promenar.infinity.data.security.SecretId, ByteArray>()
            override fun put(id: com.promenar.infinity.data.security.SecretId, value: ByteArray) { values[id] = value.copyOf() }
            override fun get(id: com.promenar.infinity.data.security.SecretId) = values[id]?.copyOf()
            override fun contains(id: com.promenar.infinity.data.security.SecretId) = id in values
            override fun remove(id: com.promenar.infinity.data.security.SecretId) { values.remove(id) }
        }
        val manager = com.promenar.infinity.data.manager.ProviderManager.createForTest(app, secrets)
        manager.updateMainProvider(com.promenar.infinity.data.remote.protocol.ProtocolType.Generic_OpenAI_Compat, "https://gateway.invalid/v1", com.promenar.infinity.data.model.CredentialUpdate.Replace("synthetic-key"), "opencode-go")
        val preferences = SessionHeaderPreferences(app.getSharedPreferences("infinity_settings", 0))
        fun name(): String? {
            val restored = com.promenar.infinity.data.manager.ProviderManager.createForTest(app, secrets)
            val router = com.promenar.infinity.data.remote.DefaultProviderRequestRouter(restored, sessionHeaderPreferences = preferences)
            val resolved = router.resolve("default::opencode-go") as com.promenar.infinity.data.remote.ProviderResolution.Success
            return resolved.value.config.sessionHeaderName
        }
        assertThat(name()).isNull()
        preferences.saveProvider("default", true, "x-custom-session")
        assertThat(name()).isEqualTo("x-custom-session")
        preferences.saveModel("default::opencode-go", SessionHeaderMode.OFF)
        assertThat(name()).isNull()
        preferences.saveProvider("default", false, "")
        preferences.saveModel("default::opencode-go", SessionHeaderMode.ON)
        manager.refreshCatalogMetadata()
        assertThat(name()).isEqualTo(SessionHeaderPolicy.DEFAULT_NAME)
    }

    @Test
    fun `旧偏好缺省关闭且校验失败不写入`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("session_header_test", 0).also { it.edit().clear().commit() }
        val store = SessionHeaderPreferences(prefs)
        assertThat(store.provider("default")).isEqualTo(ProviderSessionHeaderSettings(false, SessionHeaderPolicy.DEFAULT_NAME))
        store.saveProvider("default", true, "x-session")
        listOf("", "x session", "x:session", "Authorization", "User-Agent", "x\r\ny").forEach {
            assertThat(runCatching { store.saveProvider("default", true, it) }.isFailure).isTrue()
            assertThat(store.provider("default")).isEqualTo(ProviderSessionHeaderSettings(true, "x-session"))
        }
        assertThat(SessionHeaderPreferences(prefs).provider("other").enabled).isFalse()
        assertThat(SessionHeaderPreferences(prefs).provider("default")).isEqualTo(ProviderSessionHeaderSettings(true, "x-session"))
        assertThat(store.model("default::legacy-model")).isEqualTo(SessionHeaderMode.INHERIT)
        SessionHeaderMode.entries.forEach { mode ->
            store.saveModel("default::legacy-model", mode)
            assertThat(SessionHeaderPreferences(prefs).model("default::legacy-model")).isEqualTo(mode)
        }
        prefs.edit().putString("session_header_model_default::legacy-model", "unknown-future-value").commit()
        assertThat(store.model("default::legacy-model")).isEqualTo(SessionHeaderMode.INHERIT)
        store.removeProvider("default")
        assertThat(store.provider("default").enabled).isFalse()
    }
}
