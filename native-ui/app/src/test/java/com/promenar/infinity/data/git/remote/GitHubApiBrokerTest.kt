package com.promenar.infinity.data.git.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class GitHubApiBrokerTest {
    @Test fun `GitHub监听随租约懒启动并关闭且独立于git代理`() = runBlocking {
        val service = Endpoint()
        val nonces = GitNonceRegistry()
        GitRemoteBroker(GhTestCredentials(), nonces, null, null, githubApi = service).use { broker ->
            assertThat(service.starts).isEqualTo(0)
            broker.open().use { lease ->
                assertThat(service.starts).isEqualTo(1)
                assertThat(LinuxGitEnvironment.from(lease.environment)["GH_TOKEN"]).isEqualTo(lease.environment[GitRemoteBroker.ENV_NONCE])
                assertThat(nonces.activeCount).isEqualTo(1)
            }
            assertThat(nonces.activeCount).isEqualTo(0)
        }
        assertThat(service.closed).isTrue()
    }

    @Test fun `无GitHub凭据与启动失败不注入gh变量`() = runBlocking {
        val store = GhTestCredentials().also { it.credentials.value = emptyList() }
        val service = Endpoint()
        GitRemoteBroker(store, GitNonceRegistry(), null, null, githubApi = service).use { broker ->
            broker.open().use { assertThat(LinuxGitEnvironment.from(it.environment).keys.none { key -> key.startsWith("GH_") }).isTrue() }
            assertThat(service.starts).isEqualTo(0)
        }
        val diagnostics = mutableListOf<String>()
        val failed = Endpoint().also { it.fail = true }
        GitRemoteBroker(GhTestCredentials(), GitNonceRegistry(), null, null, diagnostics::add, failed).use { broker ->
            broker.open().use { lease ->
                assertThat(LinuxGitEnvironment.from(lease.environment).keys.none { it.startsWith("GH_") }).isTrue()
                assertThat(diagnostics.joinToString()).doesNotContain(lease.environment.getValue(GitRemoteBroker.ENV_NONCE))
                assertThat(diagnostics.joinToString()).doesNotContain("synthetic-exception-secret")
            }
        }
    }

    private class Endpoint : GitHubApiEndpoint {
        var starts = 0
        var closed = false
        var fail = false
        override fun start() { starts++; if (fail) throw IllegalStateException("synthetic-exception-secret") }
        override fun close() { closed = true }
    }
}
