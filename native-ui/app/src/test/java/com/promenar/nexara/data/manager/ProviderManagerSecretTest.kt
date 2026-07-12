package com.promenar.nexara.data.manager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.model.CredentialUpdate
import com.promenar.nexara.data.security.SecretCatalog
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

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
            credentialUpdate = CredentialUpdate.Replace("fake-secret-key"),
            model = "fake-model",
        )
        secrets.getCalls = 0
        secrets.containsThreads.clear()

        val summary = runBlocking(Dispatchers.IO) { manager.getProviderSummary("default")!! }

        assertThat(summary.hasApiKey).isTrue()
        assertThat(summary.hasVertexCredentials).isFalse()
        assertThat(summary.toString()).doesNotContain("fake-secret-key")
        assertThat(manager.providers.value.single().toString()).doesNotContain("fake-secret-key")
        assertThat(app.getSharedPreferences("nexara_provider", 0).contains("api_key")).isFalse()
        assertThat(secrets.getCalls).isEqualTo(0)
        assertThat(secrets.containsThreads).isNotEmpty()
        assertThat(secrets.containsThreads).doesNotContain(Thread.currentThread().name)
    }

    @Test
    fun `主 Vertex 配置把 JSON 写入专用 SecretId 并可在重载时取回`() {
        val credentialJson = "{\"project_id\":\"fake-project\",\"private_key\":\"fake-private\"}"
        val manager = ProviderManager.createForTest(app, secrets)

        manager.updateMainProvider(
            protocolType = ProtocolType.Google_VertexAI,
            baseUrl = "https://vertex.example.invalid",
            credentialUpdate = CredentialUpdate.Replace(credentialJson),
            model = "fake-gemini-model",
        )

        assertThat(secrets.text(SecretCatalog.vertexServiceAccount("default"))).isEqualTo(credentialJson)
        assertThat(secrets.contains(SecretCatalog.providerApiKey("default"))).isFalse()
        assertThat(manager.getMainProviderConfig()!!.vertexServiceAccountJson).isEqualTo(credentialJson)
        assertThat(manager.getProviderSummary("default")!!.hasVertexCredentials).isTrue()
    }

    @Test
    fun `集中式 ID 列表按顺序迁移多个额外 Provider`() {
        val settingsPrefs = app.getSharedPreferences("nexara_settings", 0)
        settingsPrefs.edit()
            .putInt("extra_providers_count", 2)
            .putString("extra_providers_ids", "central-a,central-b")
            .putString("extra_provider_0_name", "Central A")
            .putString("extra_provider_0_protocol", "OpenAI_ChatCompletions")
            .putString("extra_provider_0_base_url", "https://central-a.invalid")
            .putString("extra_provider_0_model", "model-a")
            .putString("extra_provider_0_api_key", "fake-a")
            .putString("extra_provider_1_name", "Central B")
            .putString("extra_provider_1_protocol", "Anthropic_Messages")
            .putString("extra_provider_1_api_key", "fake-b")
            .commit()

        val manager = ProviderManager.createForTest(app, secrets)

        assertThat(secrets.text(SecretCatalog.providerApiKey("central-a"))).isEqualTo("fake-a")
        assertThat(secrets.text(SecretCatalog.providerApiKey("central-b"))).isEqualTo("fake-b")
        assertThat(settingsPrefs.contains("extra_provider_0_api_key")).isFalse()
        assertThat(settingsPrefs.contains("extra_provider_1_api_key")).isFalse()
        assertThat(manager.getProviderConfig("central-a")!!.baseUrl).isEqualTo("https://central-a.invalid")
        assertThat(manager.getProviderConfig("central-a")!!.apiKey).isEqualTo("fake-a")
        assertThat(manager.getProviderSummary("central-a")!!.name).isEqualTo("Central A")
        assertThat(manager.getProviderSummary("central-a")!!.hasApiKey).isTrue()
    }

    @Test
    fun `迁移写入失败时保留对应旧明文字段`() {
        val providerPrefs = app.getSharedPreferences("nexara_provider", 0)
        providerPrefs.edit().putString("api_key", "fake-must-survive").commit()
        val failing = MemorySecretStore(failOn = SecretCatalog.providerApiKey("default"))

        val result = runCatching { ProviderManager.createForTest(app, failing) }

        assertThat(result.isFailure).isTrue()
        assertThat(providerPrefs.getString("api_key", null)).isEqualTo("fake-must-survive")
    }

    @Test
    fun `旧明文迁移不覆盖已存在的安全凭证`() {
        val backupPrefs = app.getSharedPreferences("nexara_backup_settings", 0)
        backupPrefs.edit().putString("webdav_pass", "stale-plaintext").commit()
        secrets.put(SecretCatalog.webDavPassword, "current-secret".encodeToByteArray())

        ProviderManager.createForTest(app, secrets)

        assertThat(secrets.text(SecretCatalog.webDavPassword)).isEqualTo("current-secret")
        assertThat(backupPrefs.contains("webdav_pass")).isFalse()
    }

    @Test
    fun `脱敏主配置保存时保留凭证而明确清除才删除`() {
        val manager = ProviderManager.createForTest(app, secrets)
        manager.updateMainProvider(
            ProtocolType.OpenAI_ChatCompletions,
            "https://before.invalid",
            CredentialUpdate.Replace("fake-preserved"),
            "model-a",
        )

        manager.updateMainProvider(
            ProtocolType.OpenAI_ChatCompletions,
            "https://after.invalid",
            CredentialUpdate.Preserve,
            "model-b",
        )
        assertThat(manager.getMainProviderConfig()!!.apiKey).isEqualTo("fake-preserved")

        manager.updateMainProvider(
            ProtocolType.OpenAI_ChatCompletions,
            "https://after.invalid",
            CredentialUpdate.Clear,
            "model-b",
        )
        assertThat(manager.getMainProviderConfig()!!.apiKey).isEmpty()
    }

    @Test
    fun `脱敏额外 Provider 编辑保留 Vertex JSON 而明确清除才删除`() {
        val manager = ProviderManager.createForTest(app, secrets)
        val item = com.promenar.nexara.data.model.ProviderListItem(
            id = "extra-vertex",
            name = "Vertex",
            protocolType = ProtocolType.Google_VertexAI,
        )
        manager.addProvider(item, CredentialUpdate.Replace("{\"private_key\":\"fake\"}"))

        manager.updateExtraProvider("extra-vertex", item.copy(name = "Vertex Renamed"), CredentialUpdate.Preserve)
        assertThat(secrets.contains(SecretCatalog.vertexServiceAccount("extra-vertex"))).isTrue()

        manager.updateExtraProvider("extra-vertex", item, CredentialUpdate.Clear)
        assertThat(secrets.contains(SecretCatalog.vertexServiceAccount("extra-vertex"))).isFalse()
    }

    @Test
    fun `API Key 到 Vertex 的 Preserve 切换原子拒绝且 Replace 才允许`() {
        val manager = ProviderManager.createForTest(app, secrets)
        manager.updateMainProvider(
            ProtocolType.OpenAI_ChatCompletions,
            "https://openai.invalid",
            CredentialUpdate.Replace("fake-api-key"),
            "openai-model",
            "OpenAI",
        )

        val rejected = runCatching {
            manager.updateMainProvider(
                ProtocolType.Google_VertexAI,
                "https://vertex.invalid",
                CredentialUpdate.Preserve,
                "vertex-model",
                "Vertex",
            )
        }

        assertThat(rejected.isFailure).isTrue()
        val unchanged = manager.getMainProviderConfig()!!
        assertThat(unchanged.protocolType).isEqualTo(ProtocolType.OpenAI_ChatCompletions)
        assertThat(unchanged.baseUrl).isEqualTo("https://openai.invalid")
        assertThat(unchanged.model).isEqualTo("openai-model")
        assertThat(unchanged.name).isEqualTo("OpenAI")
        assertThat(unchanged.apiKey).isEqualTo("fake-api-key")
        assertThat(unchanged.vertexServiceAccountJson).isEmpty()

        manager.updateMainProvider(
            ProtocolType.Google_VertexAI,
            "https://vertex.invalid",
            CredentialUpdate.Replace("{\"private_key\":\"fake-vertex\"}"),
            "vertex-model",
            "Vertex",
        )
        assertThat(manager.getMainProviderConfig()!!.vertexServiceAccountJson).contains("fake-vertex")
    }

    @Test
    fun `Vertex 到 API Key 的 Preserve 切换原子拒绝而 Clear 后状态一致`() {
        val manager = ProviderManager.createForTest(app, secrets)
        manager.updateMainProvider(
            ProtocolType.Google_VertexAI,
            "https://vertex.invalid",
            CredentialUpdate.Replace("{\"private_key\":\"fake-vertex\"}"),
            "vertex-model",
            "Vertex",
        )

        val rejected = runCatching {
            manager.updateMainProvider(
                ProtocolType.Anthropic_Messages,
                "https://anthropic.invalid",
                CredentialUpdate.Preserve,
                "claude-model",
                "Anthropic",
            )
        }

        assertThat(rejected.isFailure).isTrue()
        val unchanged = manager.getMainProviderConfig()!!
        assertThat(unchanged.protocolType).isEqualTo(ProtocolType.Google_VertexAI)
        assertThat(unchanged.baseUrl).isEqualTo("https://vertex.invalid")
        assertThat(unchanged.vertexServiceAccountJson).contains("fake-vertex")

        manager.updateMainProvider(
            ProtocolType.Anthropic_Messages,
            "https://anthropic.invalid",
            CredentialUpdate.Clear,
            "claude-model",
            "Anthropic",
        )
        val cleared = manager.getMainProviderConfig()!!
        assertThat(cleared.protocolType).isEqualTo(ProtocolType.Anthropic_Messages)
        assertThat(cleared.apiKey).isEmpty()
        assertThat(cleared.vertexServiceAccountJson).isEmpty()
        assertThat(manager.getProviderSummary("default")!!.hasApiKey).isFalse()
        assertThat(manager.getProviderSummary("default")!!.hasVertexCredentials).isFalse()
    }

    @Test
    fun `重复 ID add 在任何写入前拒绝且状态完全不变`() {
        val manager = ProviderManager.createForTest(app, secrets)
        val original = com.promenar.nexara.data.model.ProviderListItem(
            id = "duplicate-id",
            name = "Original",
            protocolType = ProtocolType.OpenAI_ChatCompletions,
        )
        manager.addProvider(original, CredentialUpdate.Replace("fake-original-key"))
        val settingsPrefs = app.getSharedPreferences("nexara_settings", 0)
        val providersBefore = manager.providers.value.toList()
        val prefsBefore = settingsPrefs.all.toMap()
        val secretsBefore = secrets.snapshot()

        val result = runCatching {
            manager.addProvider(
                original.copy(name = "Duplicate", protocolType = ProtocolType.Google_VertexAI),
                CredentialUpdate.Replace("{\"private_key\":\"fake-overwrite\"}"),
            )
        }

        assertThat(result.isFailure).isTrue()
        assertThat(manager.providers.value).containsExactlyElementsIn(providersBefore).inOrder()
        assertThat(settingsPrefs.all).isEqualTo(prefsBefore)
        assertThat(secrets.snapshot()).isEqualTo(secretsBefore)
    }

    private class MemorySecretStore(private val failOn: SecretId? = null) : SecretStore {
        private val values = mutableMapOf<SecretId, ByteArray>()
        override fun put(id: SecretId, value: ByteArray) {
            if (id == failOn) error("fake write failure")
            values[id] = value.copyOf()
        }
        var getCalls = 0
        val containsThreads = mutableListOf<String>()
        override fun get(id: SecretId): ByteArray? {
            getCalls++
            return values[id]?.copyOf()
        }
        override fun contains(id: SecretId): Boolean {
            containsThreads += Thread.currentThread().name
            return id in values
        }
        override fun remove(id: SecretId) { values.remove(id) }
        fun text(id: SecretId): String? = get(id)?.toString(Charsets.UTF_8)
        fun snapshot(): Map<SecretId, String> = values.mapValues { (_, value) -> value.toString(Charsets.UTF_8) }
    }
}
