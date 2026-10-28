package com.promenar.nexara.ui.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.model.SessionRunMode
import com.promenar.nexara.ui.settings.NetworkRunMode
import org.junit.Test

class AgentReachNetworkRunModeTest {
    @Test
    fun `会话保存了运行模式时以它为准`() {
        val readyLinux = AgentReachState(backends = listOf(ReachBackend(ReachBackendKind.LINUX, ReachBackendStatus.READY)))
        assertThat(reachNetworkRunMode(SessionRunMode.BUILTIN, readyLinux)).isEqualTo(NetworkRunMode.BUILTIN)
        assertThat(reachNetworkRunMode(SessionRunMode.LINUX, AgentReachState())).isEqualTo(NetworkRunMode.LINUX)
    }

    @Test
    fun `会话尚未选择运行模式时按 Linux 是否接管 shell 推断`() {
        val readyLinux = AgentReachState(backends = listOf(ReachBackend(ReachBackendKind.LINUX, ReachBackendStatus.READY)))
        val brokenLinux = AgentReachState(backends = listOf(ReachBackend(ReachBackendKind.LINUX, ReachBackendStatus.BROKEN)))
        assertThat(reachNetworkRunMode(null, readyLinux)).isEqualTo(NetworkRunMode.LINUX)
        assertThat(reachNetworkRunMode(null, brokenLinux)).isEqualTo(NetworkRunMode.BUILTIN)
        assertThat(reachNetworkRunMode(null, AgentReachState())).isEqualTo(NetworkRunMode.BUILTIN)
    }
}
