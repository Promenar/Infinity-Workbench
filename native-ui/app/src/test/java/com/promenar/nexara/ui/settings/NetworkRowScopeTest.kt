package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.network.AgentNetworkTool
import org.junit.Test

class NetworkRowScopeTest {
    @Test
    fun `每行适用范围：脚本仅内置模式，内置命令不受白名单约束，其余不标注`() {
        assertThat(networkRowScope(AgentNetworkTool.SCRIPT)).isEqualTo(NetworkRowScope.BUILTIN_ONLY)
        assertThat(networkRowScope(AgentNetworkTool.BUILTIN_PROCESS)).isEqualTo(NetworkRowScope.NOT_BOUND_BY_ALLOWLIST)
        listOf(
            AgentNetworkTool.WEB_FETCH, AgentNetworkTool.MEDIA_FETCH, AgentNetworkTool.MCP,
            AgentNetworkTool.TERMUX, AgentNetworkTool.SSH,
        ).forEach { assertThat(networkRowScope(it)).isEqualTo(NetworkRowScope.ALL_MODES) }
    }

    @Test
    fun `Linux 模式下只有脚本联网行灰显，内置模式与全局页全部生效`() {
        AgentNetworkTool.entries.forEach { tool ->
            assertThat(networkRowEffective(tool, NetworkRunMode.BUILTIN)).isTrue()
            assertThat(networkRowEffective(tool, null)).isTrue()
        }
        assertThat(networkRowEffective(AgentNetworkTool.SCRIPT, NetworkRunMode.LINUX)).isFalse()
        AgentNetworkTool.entries.filter { it != AgentNetworkTool.SCRIPT }.forEach {
            assertThat(networkRowEffective(it, NetworkRunMode.LINUX)).isTrue()
        }
    }
}
