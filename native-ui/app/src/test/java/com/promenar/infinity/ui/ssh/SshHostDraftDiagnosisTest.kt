package com.promenar.infinity.ui.ssh

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.ssh.SshAuthType
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 固定“添加主机”表单的现行校验事实：别名规则只作用于别名字段，
 * 且别名最先校验，别名不合法时保存、密码保存与“生成密钥”三条路径都在生成凭据前中止。
 */
class SshHostDraftDiagnosisTest {
    @Test
    fun `新建主机的别名默认为空时三条保存路径都报 SSH_ALIAS_INVALID`() {
        // 编辑面板新建主机时 alias 初值为 existing?.alias.orEmpty()，即空串。
        val emptyAlias = draft(alias = "")
        val cases = listOf(
            "密码保存" to { emptyAlias.copy(authType = SshAuthType.PASSWORD).toHost(null, false, true, false) },
            "导入私钥保存" to { emptyAlias.toHost(null, true, false, false) },
            "生成密钥" to { emptyAlias.toHost(null, false, false, true) },
        )
        cases.forEach { (path, save) ->
            val error = assertThrows(path, SshInputException::class.java) { save() }
            assertThat(error.message).isEqualTo("SSH_ALIAS_INVALID")
        }
    }

    @Test
    fun `首字母大写的用户名合法，规则提示描述的是别名`() {
        val host = draft(alias = "fnos", username = "Promenar")
            .copy(authType = SshAuthType.PASSWORD)
            .toHost(null, false, true, false)
        assertThat(host.username).isEqualTo("Promenar")
        assertThat(host.alias).isEqualTo("fnos")
    }

    @Test
    fun `常见的别名填法都会被拒绝且掩盖后续字段错误`() {
        // 大写首字母（输入法自动大写）、把 IP 或域名当别名、中文与内部空格。
        listOf("Fnos", "100.101.7.42", "fnos.local", "家里NAS", "my host").forEach { alias ->
            val error = assertThrows(alias, SshInputException::class.java) {
                // 主机名同时非法，也只报别名错误：别名最先校验。
                draft(alias = alias).copy(hostname = "").toHost(null, false, false, true)
            }
            assertThat(error.message).isEqualTo("SSH_ALIAS_INVALID")
        }
    }

    private fun draft(alias: String, username: String = "user") = SshHostDraft(
        alias = alias, hostname = "100.101.7.42", port = "22", username = username, authType = SshAuthType.KEY,
        keepTerminal = true, forwarding = false, localPort = "", forwardHost = "127.0.0.1", forwardPort = "",
    )
}
