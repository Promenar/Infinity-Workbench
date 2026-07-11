package com.promenar.nexara.data.manager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.security.SecretCatalog
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderManagerSecretTest {
    private lateinit var app: Application
    private lateinit var secrets: MemorySecretStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        listOf("nexara_provider", "nexara_settings", "nexara_search", "nexara_backup_settings")
            .forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        secrets = MemorySecretStore()
    }

    @Test
    fun `初始化会迁移主额外 Provider 和服务凭证并删除旧明文`() {
        val providerPrefs = app.getSharedPreferences("nexara_provider", 0)
        providerPrefs.edit()
            .putString("protocol_id", "OpenAI_ChatCompletions")
            .putString("api_key", "fake-main-key")
            .putString("embedding_api_key", "fake-embedding-key")
            .commit()
        val settingsPrefs = app.getSharedPreferences("nexara_settings", 0)
        settingsPrefs.edit()
            .putInt("extra_providers_count", 1)
            .putString("extra_provider_0_id", "extra-safe")
            .putString("extra_provider_0_name", "额外提供商")
            .putString("extra_provider_0_protocol", "Google_VertexAI")
            .putString("extra_provider_0_api_key", "fake-extra-key")
            .putString("extra_provider_0_vertex_service_account_json", "{\"private_key\":\"fake-private\"}")
            .commit()
        val backupPrefs = app.getSharedPreferences("nexara_backup_settings", 0)
        backupPrefs.edit()
            .putString("webdav_pass", "fake-webdav-pass")
            .putString("automatic_backup_password", "fake-backup-pass")
            .commit()

        val manager = ProviderManager.createForTest(app, secrets)

        assertThat(secrets.text(SecretCatalog.providerApiKey("default"))).isEqualTo("fake-main-key")
        assertThat(secrets.text(SecretCatalog.providerApiKey("extra-safe"))).isEqualTo("fake-extra-key")
        assertThat(secrets.text(SecretCatalog.vertexServiceAccount("extra-safe")))
            .isEqualTo("{\"private_key\":\"fake-private\"}")
        assertThat(secrets.text(SecretCatalog.embeddingApiKey)).isEqualTo("fake-embedding-key")
        assertThat(secrets.text(SecretCatalog.webDavPassword)).isEqualTo("fake-webdav-pass")
        assertThat(secrets.text(SecretCatalog.automaticBackupPassword)).isEqualTo("fake-backup-pass")
        assertThat(providerPrefs.contains("api_key")).isFalse()
        assertThat(providerPrefs.contains("embedding_api_key")).isFalse()
        assertThat(settingsPrefs.contains("extra_provider_0_api_key")).isFalse()
        assertThat(settingsPrefs.contains("extra_provider_0_vertex_service_account_json")).isFalse()
        assertThat(backupPrefs.contains("webdav_pass")).isFalse()
        assertThat(backupPrefs.contains("automatic_backup_password")).isFalse()
        assertThat(manager.getMainProviderConfig()!!.apiKey).isEqualTo("fake-main-key")
    }

    @Test
    fun `提供商摘要只暴露凭证存在性且列表不携带完整 Key`() {
        val manager = ProviderManager.createForTest(app, secrets)
        manager.updateMainProvider(
            protocolType = ProtocolType.OpenAI_ChatCompletions,
            baseUrl = "https://example.invalid",
            apiKey = "fake-secret-key",
            model = "fake-model",
        )

        val summary = manager.getProviderSummary("default")!!

        assertThat(summary.hasApiKey).isTrue()
        assertThat(summary.hasVertexCredentials).isFalse()
        assertThat(summary.toString()).doesNotContain("fake-secret-key")
        assertThat(manager.providers.value.single().toString()).doesNotContain("fake-secret-key")
        assertThat(app.getSharedPreferences("nexara_provider", 0).contains("api_key")).isFalse()
    }

    @Test
    fun `主 Vertex 配置把 JSON 写入专用 SecretId 并可在重载时取回`() {
        val credentialJson = "{\"project_id\":\"fake-project\",\"private_key\":\"fake-private\"}"
        val manager = ProviderManager.createForTest(app, secrets)

        manager.updateMainProvider(
            protocolType = ProtocolType.Google_VertexAI,
            baseUrl = "https://vertex.example.invalid",
            apiKey = credentialJson,
            model = "fake-gemini-model",
        )

        assertThat(secrets.text(SecretCatalog.vertexServiceAccount("default"))).isEqualTo(credentialJson)
        assertThat(secrets.contains(SecretCatalog.providerApiKey("default"))).isFalse()
        assertThat(manager.getMainProviderConfig()!!.vertexServiceAccountJson).isEqualTo(credentialJson)
        assertThat(manager.getProviderSummary("default")!!.hasVertexCredentials).isTrue()
    }

    private class MemorySecretStore : SecretStore {
        private val values = mutableMapOf<SecretId, ByteArray>()
        override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
        override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
        override fun contains(id: SecretId): Boolean = id in values
        override fun remove(id: SecretId) { values.remove(id) }
        fun text(id: SecretId): String? = get(id)?.toString(Charsets.UTF_8)
    }
}
