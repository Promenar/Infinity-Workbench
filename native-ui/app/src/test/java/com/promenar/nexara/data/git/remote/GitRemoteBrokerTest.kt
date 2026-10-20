package com.promenar.nexara.data.git.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test

class GitRemoteBrokerTest {
    private val token = "nxt_BrokerSyntheticToken"
    private val store = SecretGitCredentialStore(MemorySecrets())
    private val nonces = GitNonceRegistry()

    @Test fun `未配置凭据时不启动代理只签发nonce与SSH桥地址`() = runBlocking {
        val proxy = GitCredentialProxy(store, nonces, OkHttpClient())
        val bridge = GitSshBridge(nonces, { emptyList() }, { _, _, _, _, _, _, _ -> 0 })
        GitRemoteBroker(store, nonces, proxy, bridge).use { broker ->
            broker.open().use { lease ->
                assertThat(lease.environment.keys).containsExactly(GitRemoteBroker.ENV_NONCE, GitRemoteBroker.ENV_SSH_BRIDGE)
                assertThat(lease.environment.getValue(GitRemoteBroker.ENV_SSH_BRIDGE)).isEqualTo("127.0.0.1:${bridge.port}")
                assertThat(proxy.port).isNull()
            }
        }
    }

    @Test fun `配置凭据后环境变量只含nonce代理地址与主机列表且关闭即吊销`() = runBlocking {
        store.save("github.com", "octocat", token.toByteArray())
        store.save("git.example.com:8443", "ci", token.toByteArray())
        val proxy = GitCredentialProxy(store, nonces, OkHttpClient())
        GitRemoteBroker(store, nonces, proxy, sshBridge = null).use { broker ->
            val first = broker.open()
            val second = broker.open()
            assertThat(first.environment.getValue(GitRemoteBroker.ENV_NONCE)).isNotEqualTo(second.environment.getValue(GitRemoteBroker.ENV_NONCE))
            assertThat(first.environment.getValue(GitRemoteBroker.ENV_HTTP_PROXY)).isEqualTo("127.0.0.1:${proxy.port}")
            assertThat(first.environment.getValue(GitRemoteBroker.ENV_CREDENTIAL_HOSTS)).isEqualTo("git.example.com:8443,github.com")
            assertThat(first.environment.toString()).doesNotContain(token)
            first.close()
            assertThat(nonces.resolve(first.environment.getValue(GitRemoteBroker.ENV_NONCE))).isNull()
            assertThat(nonces.resolve(second.environment.getValue(GitRemoteBroker.ENV_NONCE))).isNotNull()
            second.close()
            assertThat(nonces.activeCount).isEqualTo(0)
        }
    }

    @Test fun `代理启动失败时不设置代理变量`() = runBlocking {
        store.save("github.com", "octocat", token.toByteArray())
        val proxy = GitCredentialProxy(store, nonces, OkHttpClient()).also { it.close() }
        val diagnostics = mutableListOf<String>()
        GitRemoteBroker(store, nonces, proxy, sshBridge = null, diagnostics = diagnostics::add).use { broker ->
            // 已关闭的代理拒绝再启动，命令退回直连。
            broker.open().use { lease ->
                assertThat(lease.environment.keys).containsExactly(GitRemoteBroker.ENV_NONCE)
                assertThat(diagnostics.single()).isEqualTo("git-broker: proxy IllegalStateException")
            }
        }
    }
}
