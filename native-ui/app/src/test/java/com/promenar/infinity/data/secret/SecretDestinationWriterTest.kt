package com.promenar.infinity.data.secret

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.git.remote.SecretGitCredentialStore
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.security.*
import com.promenar.infinity.data.ssh.*
import com.promenar.infinity.domain.secret.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class SecretDestinationWriterTest {
    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<SecretId, ByteArray>()
        override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
        override fun get(id: SecretId) = values[id]?.copyOf()
        override fun contains(id: SecretId) = id in values
        override fun remove(id: SecretId) { values.remove(id)?.fill(0) }
    }
    private val store = MemorySecrets()
    private val git = SecretGitCredentialStore(store)
    private val vault = SessionSecretVault()
    private val configuredHost = SshHost(id = "host-id", alias = "fnos", hostname = "example.invalid", username = "user")
    private var savedHost: SshHost? = null
    private var savedCredential: SshCredential? = null
    private var passedCredential: SshCredential? = null
    private val hosts = object : SshHostStore {
        override fun observeHosts() = MutableStateFlow(listOf(configuredHost))
        override suspend fun find(alias: String) = configuredHost.takeIf { it.alias == alias }
        override suspend fun save(host: SshHost, credential: SshCredential?) {
            savedHost = host; passedCredential = credential
            savedCredential = credential?.let { SshCredential(it.privateKey.copyOf(), it.password.copyOf(), it.passphrase.copyOf()) }
        }
        override suspend fun delete(alias: String) = Unit
        override suspend fun trust(alias: String, expectedOld: String?, key: SshHostKey, expectedHost: SshHost) = false
        override fun credential(host: SshHost) = SshCredential("old-key".toByteArray(), "old-password".toByteArray(), "old-passphrase".toByteArray())
    }
    private lateinit var provider: ProviderManager
    private lateinit var writer: DefaultSecretDestinationWriter

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        listOf("infinity_provider", "infinity_settings").forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        provider = ProviderManager.createForTest(app, store)
        provider.updateMainProvider(ProtocolType.OpenAI_ChatCompletions, "https://example.invalid",
            credentialUpdate = com.promenar.infinity.data.model.CredentialUpdate.Preserve, model = "test")
        writer = DefaultSecretDestinationWriter({ git }, hosts, { provider }, { error("任务服务未使用") }, vault)
    }
    @After fun cleanup() { savedCredential?.close() }

    @Test fun `Git主机规范化与已有用户名由应用生成`() = runBlocking {
        val destination = SecretDestination.GitCredential("https://GitHub.com/")
        val before = writer.describe("s", destination)
        assertThat(before.needsUsername).isTrue()
        assertThat(before.offersGitHubBrowserLogin).isTrue()
        assertThat(before.label).isEqualTo("Git 凭据 · github.com")
        writer.write("s", destination, "synthetic-git-token".toByteArray(), "alice")
        assertThat(git.username("github.com")).isEqualTo("alice")
        assertThat(git.token("github.com")!!.decodeToString()).isEqualTo("synthetic-git-token")
        assertThat(writer.describe("s", destination).needsUsername).isFalse()
    }

    @Test fun `SSH仅替换指定字段保留配置并清零中间凭据`() = runBlocking {
        writer.write("s", SecretDestination.SshHost("host-id", SshSecretField.PASSWORD), "synthetic-password".toByteArray(), null)
        assertThat(savedHost).isEqualTo(configuredHost)
        assertThat(savedCredential!!.privateKey.decodeToString()).isEqualTo("old-key")
        assertThat(savedCredential!!.password.decodeToString()).isEqualTo("synthetic-password")
        assertThat(savedCredential!!.passphrase.decodeToString()).isEqualTo("old-passphrase")
        assertThat(passedCredential!!.password.all { it == 0.toByte() }).isTrue()
        writer.write("s", SecretDestination.SshHost("host-id", SshSecretField.KEY_PASSPHRASE), "synthetic-phrase".toByteArray(), null)
        assertThat(savedCredential!!.password.decodeToString()).isEqualTo("old-password")
        assertThat(savedCredential!!.passphrase.decodeToString()).isEqualTo("synthetic-phrase")
    }

    @Test fun `服务商密钥只进入加密SecretStore`() = runBlocking {
        writer.write("s", SecretDestination.ProviderApiKey("default"), "synthetic-api-key".toByteArray(), null)
        assertThat(store.get(SecretCatalog.providerApiKey("default"))!!.decodeToString()).isEqualTo("synthetic-api-key")
        assertThat(provider.getProviderSummary("default")!!.toString()).doesNotContain("synthetic-api-key")
        assertThat(ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("infinity_provider", 0).contains("api_key")).isFalse()
        assertThat(runCatching { writer.describe("s", SecretDestination.ProviderApiKey("missing")) }.exceptionOrNull())
            .isInstanceOf(SecretIoFailure::class.java)
    }

    @Test fun `环境变量写入保密库并只有引用时注入`() = runBlocking {
        writer.write("s", SecretDestination.SessionEnv("TEST_TOKEN"), "synthetic-session-token".toByteArray(), null)
        assertThat(vault.environment("s", "true")).isEmpty()
        assertThat(vault.environment("s", "command \$TEST_TOKEN")).containsEntry("TEST_TOKEN", "synthetic-session-token")
    }

    @Test fun `输出卡显示与另存限制和销毁状态`() = runBlocking {
        vault.put("s", "TEST_TOKEN", "令牌", SecretSource.UserInput, "synthetic-output".toByteArray())
        vault.show("s", "assistant", "call", "TEST_TOKEN", "交付")
        val controller = DefaultSecretOutputController(vault, writer)
        assertThat(controller.observeCards("s").first().single().toolCallId).isEqualTo("call")
        val revealed = controller.reveal("s", "TEST_TOKEN")!!
        assertThat(String(revealed)).isEqualTo("synthetic-output")
        revealed.fill('\u0000')
        assertThat(controller.saveAs("s", "TEST_TOKEN", SecretDestination.SessionEnv("OTHER"), null)).isInstanceOf(SecretInputOutcome.Failed::class.java)
        assertThat(controller.saveAs("s", "TEST_TOKEN", SecretDestination.GitCredential("github.com"), "alice")).isInstanceOf(SecretInputOutcome.Saved::class.java)
        controller.destroy("s", "TEST_TOKEN")
        assertThat(controller.reveal("s", "TEST_TOKEN")).isNull()
        assertThat(controller.observeCards("s").first().single().slot.state).isEqualTo(SecretSlotState.DESTROYED)
        assertThat(runCatching { vault.environment("s", "command \$TEST_TOKEN") }.isFailure).isTrue()
    }
}
