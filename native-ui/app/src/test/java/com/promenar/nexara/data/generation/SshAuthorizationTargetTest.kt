package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.data.repository.SessionToolGrantStore
import com.promenar.nexara.data.security.SshCredential
import com.promenar.nexara.data.ssh.SshHost
import com.promenar.nexara.data.ssh.SshHostKey
import com.promenar.nexara.data.ssh.SshHostStore
import com.promenar.nexara.domain.tool.ToolExecutionTargets
import com.promenar.nexara.domain.tool.ToolRisk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SshAuthorizationTargetTest {
    @get:Rule val temporary = TemporaryFolder()
    private val session = Session(id = "chat", agentId = "agent")
    private val original = SshHost(id = "host-1", alias = "lab", hostname = "192.0.2.1", username = "user", trustedKeyType = "ssh-ed25519", trustedFingerprint = "SHA256:synthetic-one")
    private val hosts = TargetHosts(original)
    private val operations = listOf(
        "ssh_exec" to """{"host":"lab","command":"rm -f /tmp/x"}""",
        "ssh_session_open" to """{"host":"lab"}""",
        "ssh_session_send" to """{"session_id":"pty","input":"rm -f /tmp/x\n"}""",
        "sftp_list" to """{"host":"lab","path":"/tmp/x"}""",
        "sftp_read" to """{"host":"lab","path":"/tmp/x"}""",
        "sftp_write" to """{"host":"lab","path":"/tmp/x","content":"x"}""",
    )

    @Test fun `改地址后旧会话授权不命中`() = changedIdentity { it.copy(hostname = "192.0.2.2") }
    @Test fun `改端口后旧会话授权不命中`() = changedIdentity { it.copy(port = 2222) }
    @Test fun `改用户名后旧会话授权不命中`() = changedIdentity { it.copy(username = "other") }
    @Test fun `删除后同名重建旧会话授权不命中`() = changedIdentity(deleteFirst = true) { it.copy(id = "host-2") }
    @Test fun `重新信任指纹后旧会话授权不命中`() = changedIdentity { it.copy(trustedFingerprint = "SHA256:synthetic-two") }

    @Test fun `身份不变时授权正常命中且显示保留别名`() = runTest {
        val grants = SessionToolGrantStore(temporary.newFolder())
        operations.forEach { (name, args) ->
            val target = requireNotNull(resolve(name, args))
            grants.grant("chat", name, target)
            hosts.current = original.copy(keepTerminal = true)
            assertThat(grants.contains("chat", name, requireNotNull(resolve(name, args)))).isTrue()
            assertThat(ToolExecutionTargets.display(target)).startsWith("lab · ")
        }
    }

    @Test fun `旧格式别名授权不匹配任何SSH操作`() = runTest {
        val grants = SessionToolGrantStore(temporary.newFolder())
        operations.forEach { (name, args) ->
            val legacy = when (name) {
                "ssh_exec" -> """["process","lab","rm -f /tmp/x"]"""
                "ssh_session_open" -> """["process","lab","[PTY]"]"""
                "ssh_session_send" -> """["process","lab","rm -f /tmp/x\n"]"""
                else -> """["remote_path","lab","/tmp/x"]"""
            }
            grants.grant("chat", name, legacy)
            assertThat(grants.contains("chat", name, requireNotNull(resolve(name, args)))).isFalse()
        }
    }

    @Test fun `主机删除或未配置时没有可记住的授权目标`() = runTest {
        hosts.delete("lab")
        operations.forEach { (name, args) -> assertThat(resolve(name, args)).isNull() }
    }

    private fun changedIdentity(deleteFirst: Boolean = false, change: (SshHost) -> SshHost) = runTest {
        val grants = SessionToolGrantStore(temporary.newFolder())
        operations.forEach { (name, args) ->
            hosts.current = original
            val target = requireNotNull(resolve(name, args))
            grants.grant("chat", name, target)
            if (deleteFirst) {
                hosts.delete("lab")
                assertThat(resolve(name, args)).isNull()
            }
            hosts.save(change(original))
            val changed = requireNotNull(resolve(name, args))
            assertThat(changed).isNotEqualTo(target)
            assertThat(grants.contains("chat", name, changed)).isFalse()
        }
    }

    private suspend fun resolve(name: String, arguments: String): String? = resolveSessionToolTarget(
        session, ProtocolTool(function = ProtocolToolFunction(name, "测试", "{}"), risk = ToolRisk.PROCESS),
        arguments, null, sshHostStore = hosts, sshSessionHost = { _, id -> "lab".takeIf { id == "pty" } },
    )

    private class TargetHosts(initial: SshHost) : SshHostStore {
        private val values = MutableStateFlow(listOf(initial))
        var current: SshHost
            get() = values.value.single()
            set(value) { values.value = listOf(value) }
        override fun observeHosts() = values
        override suspend fun find(alias: String) = values.value.singleOrNull { it.alias == alias }
        override suspend fun save(host: SshHost, credential: SshCredential?) { current = host }
        override suspend fun delete(alias: String) { values.value = values.value.filterNot { it.alias == alias } }
        override suspend fun trust(alias: String, expectedOld: String?, key: SshHostKey, expectedHost: SshHost) = false
        override fun credential(host: SshHost): SshCredential? = null
    }
}
