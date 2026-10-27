package com.promenar.nexara.data.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.SessionHeaderMode
import com.promenar.nexara.data.model.SessionHeaderPolicy
import org.junit.jupiter.api.Test

class SessionHeaderPolicyTest {
    @Test
    fun `默认关闭与模型优先级`() {
        assertThat(SessionHeaderPolicy.resolveName(false, "", SessionHeaderMode.INHERIT)).isNull()
        assertThat(SessionHeaderPolicy.resolveName(true, "x-session", SessionHeaderMode.OFF)).isNull()
        assertThat(SessionHeaderPolicy.resolveName(false, "", SessionHeaderMode.ON)).isEqualTo("x-opencode-session")
        assertThat(SessionHeaderPolicy.resolveName(true, "x-session", SessionHeaderMode.INHERIT)).isEqualTo("x-session")
        assertThat(SessionHeaderPolicy.resolveName(false, "x-session", SessionHeaderMode.INHERIT)).isNull()
    }

    @Test
    fun `请求头名称必须是 ASCII token 且不覆盖协议字段`() {
        assertThat(SessionHeaderPolicy.isValidName("x-opencode-session")).isTrue()
        assertThat(SessionHeaderPolicy.isValidName("!#$%&'*+-.^_`|~09AZaz")).isTrue()
        listOf("", "x session", "x:session", "x\r\nsession", "会话", "User-Agent", "Authorization", "Content-Type").forEach {
            assertThat(SessionHeaderPolicy.isValidName(it)).isFalse()
        }
    }

    @Test
    fun `同一会话可复算且分支子会话与安装隔离`() {
        val id = SessionHeaderPolicy.identifier("installation-salt", "internal-conversation-id")
        assertThat(id).matches("[0-9a-f]{32}")
        assertThat(id).doesNotContain("internal-conversation-id")
        repeat(4) { assertThat(SessionHeaderPolicy.identifier("installation-salt", "internal-conversation-id")).isEqualTo(id) }
        listOf("branch-id", "child-id", "another-conversation").forEach {
            assertThat(SessionHeaderPolicy.identifier("installation-salt", it)).isNotEqualTo(id)
        }
        assertThat(SessionHeaderPolicy.identifier("other-installation", "internal-conversation-id")).isNotEqualTo(id)
    }
}
