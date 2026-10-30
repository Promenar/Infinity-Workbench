package com.promenar.infinity.ui.ssh

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.ssh.SshHost
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * “添加主机”的主机 ID 回归：留空时按主机名生成，输入只做小写化，
 * 校验规则本身不放宽（大写、IP、域名、中文、空格仍然被拒）。
 */
class SshHostDraftDiagnosisTest {
    @Test
    fun `新建主机 ID 留空时三条保存路径都按主机名生成`() {
        val emptyAlias = draft(alias = "")
        val saves = listOf(
            "密码保存" to { emptyAlias.copy(authType = SshAuthType.PASSWORD).toHost(null, false, true, false) },
            "导入私钥保存" to { emptyAlias.toHost(null, true, false, false) },
            "生成密钥" to { emptyAlias.toHost(null, false, false, true) },
        )
        saves.forEach { (path, save) -> assertWithMessage(path).that(save().alias).isEqualTo("host-100-101-7-42") }
        assertThat(draft(alias = " ").copy(hostname = "fnos.example-tailnet.ts.net").toHost(null, false, false, true).alias)
            .isEqualTo("fnos")
    }

    @Test
    fun `主机 ID 与主机名都空时仍报 SSH_ALIAS_INVALID，编辑已有主机不自动生成`() {
        val error = assertThrows(SshInputException::class.java) {
            draft(alias = "").copy(hostname = "").toHost(null, false, false, true)
        }
        assertThat(error.message).isEqualTo("SSH_ALIAS_INVALID")
        val existing = SshHost(alias = "fnos", hostname = "100.101.7.42", username = "user")
        assertThat(assertThrows(SshInputException::class.java) { draft(alias = "").toHost(existing, false, false, false) }.message)
            .isEqualTo("SSH_ALIAS_INVALID")
    }

    @Test
    fun `新建主机不得与已有主机 ID 重复，生成值自动避让`() {
        val taken = setOf("fnos", "fnos-2")
        assertThat(assertThrows(SshInputException::class.java) {
            draft(alias = "fnos").toHost(null, false, false, true, taken)
        }.message).isEqualTo("SSH_ALIAS_TAKEN")
        assertThat(draft(alias = "").copy(hostname = "fnos.local").toHost(null, false, false, true, taken).alias)
            .isEqualTo("fnos-3")
        // 编辑已有主机时自身 ID 在集合内属于正常情况。
        val existing = SshHost(alias = "fnos", hostname = "100.101.7.42", username = "user")
        assertThat(draft(alias = "fnos").toHost(existing, false, false, false, taken).alias).isEqualTo("fnos")
    }

    @Test
    fun `首字母大写的用户名合法，规则只约束主机 ID`() {
        val host = draft(alias = "fnos", username = "Promenar")
            .copy(authType = SshAuthType.PASSWORD)
            .toHost(null, false, true, false)
        assertThat(host.username).isEqualTo("Promenar")
        assertThat(host.alias).isEqualTo("fnos")
    }

    @Test
    fun `校验规则不放宽，输入框的小写化让大写首字母变为合法`() {
        listOf("Fnos", "100.101.7.42", "fnos.local", "家里NAS", "my host").forEach { alias ->
            val error = assertThrows(alias, SshInputException::class.java) {
                draft(alias = alias).copy(hostname = "").toHost(null, false, false, true)
            }
            assertThat(error.message).isEqualTo("SSH_ALIAS_INVALID")
        }
        val typed = SshHostDraft.normalizeAliasInput("Fnos")
        assertThat(typed).isEqualTo("fnos")
        assertThat(draft(alias = typed).toHost(null, false, false, true).alias).isEqualTo("fnos")
    }

    @Test
    fun `由主机名生成的主机 ID 总是满足规则`() {
        val rule = Regex("^[a-z][a-z0-9_-]{0,47}$")
        val cases = mapOf(
            "192.168.1.10" to "host-192-168-1-10",
            "[fd7a:115c:a1e0::1]" to "host-fd7a-115c-a1e0-1",
            "Mac-Mini.local" to "mac-mini",
            "家里NAS.local" to "nas",
            "1password.example.com" to "host-1password",
            "a".repeat(80) + ".example.com" to "a".repeat(48),
        )
        cases.forEach { (hostname, expected) ->
            val alias = SshHostDraft.suggestAlias(hostname)
            assertWithMessage(hostname).that(alias).isEqualTo(expected)
            assertWithMessage(hostname).that(rule.matches(alias!!)).isTrue()
        }
        assertThat(SshHostDraft.suggestAlias("")).isNull()
        assertThat(SshHostDraft.suggestAlias("家里.local")).isNull()
        val long = SshHostDraft.suggestAlias("a".repeat(80), setOf("a".repeat(48)))!!
        assertThat(long).hasLength(48)
        assertThat(long).endsWith("-2")
    }

    private fun draft(alias: String, username: String = "user") = SshHostDraft(
        alias = alias, hostname = "100.101.7.42", port = "22", username = username, authType = SshAuthType.KEY,
        keepTerminal = true, forwarding = false, localPort = "", forwardHost = "127.0.0.1", forwardPort = "",
    )
}
