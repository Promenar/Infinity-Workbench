package com.promenar.nexara.ui.ssh

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.ssh.SshAuthType
import com.promenar.nexara.data.ssh.SshHost
import org.junit.Assert.assertThrows
import org.junit.Test

class SshHostDraftTest {
    @Test
    fun `生成密钥时规范空白并保留转发和终端配置`() {
        val host = draft().copy(
            alias = " local ", hostname = " 127.0.0.1 ", username = " user ", port = "8022",
            keepTerminal = true, forwarding = true, localPort = "8080", forwardHost = " localhost ", forwardPort = "80",
        ).toHost(null, false, false, true)

        assertThat(host.alias).isEqualTo("local")
        assertThat(host.hostname).isEqualTo("127.0.0.1")
        assertThat(host.username).isEqualTo("user")
        assertThat(host.port).isEqualTo(8022)
        assertThat(host.authType).isEqualTo(SshAuthType.KEY)
        assertThat(host.keepTerminal).isTrue()
        assertThat(host.localForwardPort).isEqualTo(8080)
        assertThat(host.forwardHost).isEqualTo("localhost")
        assertThat(host.forwardPort).isEqualTo(80)
    }

    @Test
    fun `非法别名地址端口与转发只返回固定代码`() {
        val cases = listOf(
            draft().copy(alias = "UPPER") to "SSH_ALIAS_INVALID",
            draft().copy(hostname = "host\ncommand") to "SSH_HOST_INVALID",
            draft().copy(port = "0") to "SSH_PORT_INVALID",
            draft().copy(port = "65536") to "SSH_PORT_INVALID",
            draft().copy(username = " ") to "SSH_USERNAME_INVALID",
            draft().copy(forwarding = true, localPort = "1", forwardPort = "65536") to "SSH_FORWARD_INVALID",
        )
        cases.forEach { (draft, expected) ->
            val error = assertThrows(SshInputException::class.java) { draft.toHost(null, false, false, true) }
            assertThat(error.message).isEqualTo(expected)
        }
    }

    @Test
    fun `新增主机需要认证材料同方式编辑可保留已有凭据`() {
        assertThat(assertThrows(SshInputException::class.java) { draft().toHost(null, false, false, false) }.message)
            .isEqualTo("SSH_KEY_REQUIRED")
        val existing = SshHost(id = "fixed", alias = "local", hostname = "old", username = "user", trustedFingerprint = "SHA256:known")
        val edited = draft().toHost(existing, false, false, false)

        assertThat(edited.id).isEqualTo("fixed")
        assertThat(edited.trustedFingerprint).isEqualTo("SHA256:known")
        assertThat(edited.hostname).isEqualTo("127.0.0.1")
    }

    @Test
    fun `切换认证方式需要新凭据且不允许编辑主机别名`() {
        val existing = SshHost(alias = "local", hostname = "host", username = "user")
        val password = draft().copy(authType = SshAuthType.PASSWORD)
        assertThat(assertThrows(SshInputException::class.java) { password.toHost(existing, false, false, false) }.message)
            .isEqualTo("SSH_PASSWORD_REQUIRED")
        assertThat(password.toHost(existing, false, true, false).authType).isEqualTo(SshAuthType.PASSWORD)
        assertThat(assertThrows(SshInputException::class.java) {
            draft().copy(alias = "changed").toHost(existing, false, false, false)
        }.message).isEqualTo("SSH_ALIAS_IMMUTABLE")
    }

    @Test
    fun `未启用转发时空目标恢复loopback且端口不进入配置`() {
        val host = draft().copy(forwardHost = "", localPort = "bad", forwardPort = "bad").toHost(null, true, false, false)
        assertThat(host.localForwardPort).isNull()
        assertThat(host.forwardPort).isNull()
        assertThat(host.forwardHost).isEqualTo("127.0.0.1")
    }

    private fun draft() = SshHostDraft(
        alias = "local", hostname = "127.0.0.1", port = "22", username = "user", authType = SshAuthType.KEY,
        keepTerminal = false, forwarding = false, localPort = "", forwardHost = "127.0.0.1", forwardPort = "",
    )
}
