package com.promenar.nexara.ui.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.termux.TermuxAvailability
import com.promenar.nexara.domain.linux.LinuxEnvironmentInfo
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxUnavailableReason
import com.promenar.nexara.domain.network.AgentNetworkSnapshot
import com.promenar.nexara.domain.network.NetworkAccessState
import com.promenar.nexara.domain.network.NetworkTargetClass
import com.promenar.nexara.domain.storage.ExternalMount
import com.promenar.nexara.ui.settings.NetworkCellMeaning
import com.promenar.nexara.ui.settings.NetworkCellTone
import com.promenar.nexara.ui.settings.networkCell
import org.junit.Test

class AgentReachModelsTest {
    @Test
    fun `workspace traits follow the protection each workspace kind actually has`() {
        assertThat(workspaceTraits(null)).isEqualTo(WorkspaceTraits(tracked = true, backedUp = true, termuxVisible = false, survivesUninstall = false))
        assertThat(workspaceTraits(WorkspaceEntity.KIND_PROJECT)).isEqualTo(WorkspaceTraits(tracked = false, backedUp = false, termuxVisible = false, survivesUninstall = false))
        assertThat(workspaceTraits(WorkspaceEntity.KIND_SHARED)).isEqualTo(WorkspaceTraits(tracked = false, backedUp = false, termuxVisible = true, survivesUninstall = true))
    }

    @Test
    fun `unavailable and loading workspaces expose no traits`() {
        assertThat(SessionWorkspaceUi.Loading.traits).isNull()
        assertThat(SessionWorkspaceUi.Unavailable("w", null, null, null, "gone").traits).isNull()
        assertThat(SessionWorkspaceUi.Directory("w", "repo", WorkspaceEntity.KIND_SHARED, "/storage/emulated/0/repo").traits?.termuxVisible).isTrue()
    }

    @Test
    fun `挂载隐藏保留技能别名并按会话别名排序`() {
        val mounts = listOf(
            mount("skills", "builtin_skills", "s1"),
            mount("termux-home", "saf_tree", "s1"),
            mount("downloads", "real_path", "s1"),
            mount("broken", "saf_tree", "s1", unavailable = "revoked"),
        )
        val reach = reachMounts(mounts)
        assertThat(reach.map { it.alias }).containsExactly("broken", "downloads", "termux-home").inOrder()
        assertThat(reach.first { it.alias == "downloads" }).isEqualTo(ReachMount("downloads", ReachMountSource.PATH, commandAccessible = true, unavailable = false))
        assertThat(reach.first { it.alias == "termux-home" }.commandAccessible).isFalse()
        assertThat(reach.first { it.alias == "broken" }.unavailable).isTrue()
    }

    @Test
    fun `termux status is disabled before the integration is turned on`() {
        assertThat(termuxReachStatus(false, TermuxAvailability(true, "0.119.1", true))).isEqualTo(ReachBackendStatus.DISABLED)
        assertThat(termuxReachStatus(true, null)).isEqualTo(ReachBackendStatus.LOADING)
        assertThat(termuxReachStatus(true, TermuxAvailability(false, null, false))).isEqualTo(ReachBackendStatus.NOT_INSTALLED)
        assertThat(termuxReachStatus(true, TermuxAvailability(true, "0.108.0", true))).isEqualTo(ReachBackendStatus.UNSUPPORTED_VERSION)
        assertThat(termuxReachStatus(true, TermuxAvailability(true, "0.119.1", false))).isEqualTo(ReachBackendStatus.PERMISSION_REQUIRED)
        assertThat(termuxReachStatus(true, TermuxAvailability(true, "0.119.1", true))).isEqualTo(ReachBackendStatus.READY)
    }

    @Test
    fun `backends report missing python and missing ssh hosts`() {
        val backends = reachBackends(
            pythonAvailable = false, termuxEnabled = false, termuxAvailability = null, sshHostCount = 0,
            linuxState = LinuxRuntimeState.NotInstalled(0), linuxAgentEnabled = true,
        )
        assertThat(backends.map { it.kind }).containsExactlyElementsIn(ReachBackendKind.entries).inOrder()
        assertThat(backends.first { it.kind == ReachBackendKind.BUILTIN }.status).isEqualTo(ReachBackendStatus.SHELL_ONLY)
        assertThat(backends.first { it.kind == ReachBackendKind.SANDBOX }.status).isEqualTo(ReachBackendStatus.JS_ONLY)
        assertThat(backends.first { it.kind == ReachBackendKind.SSH }.status).isEqualTo(ReachBackendStatus.NO_HOSTS)
        val ready = reachBackends(pythonAvailable = true, termuxEnabled = false, termuxAvailability = null, sshHostCount = 3)
        assertThat(ready.first { it.kind == ReachBackendKind.SSH }).isEqualTo(ReachBackend(ReachBackendKind.SSH, ReachBackendStatus.READY, 3))
        assertThat(reachBackends(true, false, null, null).first { it.kind == ReachBackendKind.SSH }.status).isEqualTo(ReachBackendStatus.LOADING)
    }

    @Test
    fun `linux row is omitted only when the distribution has no linux environment`() {
        fun linux(state: LinuxRuntimeState) =
            reachBackends(true, false, null, 0, linuxState = state, linuxAgentEnabled = true).firstOrNull { it.kind == ReachBackendKind.LINUX }
        assertThat(linux(LinuxRuntimeState.Unavailable(LinuxUnavailableReason.DISTRIBUTION))).isNull()
        assertThat(linux(LinuxRuntimeState.Unavailable(LinuxUnavailableReason.UNSUPPORTED_ABI))?.status).isEqualTo(ReachBackendStatus.UNAVAILABLE)
        assertThat(linux(LinuxRuntimeState.NotInstalled(0))?.status).isEqualTo(ReachBackendStatus.NOT_INSTALLED)
    }

    @Test
    fun `shell is attributed to linux only when the environment is ready and enabled`() {
        val ready = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "3.24.3", "aarch64", "test", 1, null))
        fun runsShell(state: LinuxRuntimeState, enabled: Boolean) =
            reachBackends(true, false, null, 0, linuxState = state, linuxAgentEnabled = enabled).linuxRunsShell()
        assertThat(runsShell(ready, enabled = true)).isTrue()
        assertThat(runsShell(ready, enabled = false)).isFalse()
        assertThat(runsShell(LinuxRuntimeState.NotInstalled(0), enabled = true)).isFalse()
        assertThat(runsShell(LinuxRuntimeState.Unavailable(LinuxUnavailableReason.DISTRIBUTION), enabled = true)).isFalse()
    }

    @Test
    fun `linux home workspace is untracked, not backed up and gone with the app`() {
        assertThat(workspaceTraits(WorkspaceEntity.KIND_LINUX_HOME))
            .isEqualTo(WorkspaceTraits(tracked = false, backedUp = false, termuxVisible = false, survivesUninstall = false))
    }

    @Test
    fun `network cells reflect the current allowlist and tailnet state`() {
        val empty = snapshot()
        assertThat(networkCell(NetworkTargetClass.LAN, NetworkAccessState.ALLOWLIST_ONLY, empty).meaning).isEqualTo(NetworkCellMeaning.ALLOWLIST_EMPTY)
        assertThat(networkCell(NetworkTargetClass.LAN, NetworkAccessState.ALLOWLIST_ONLY, empty).tone).isEqualTo(NetworkCellTone.CLOSED)
        val hosts = snapshot(lanHosts = listOf("nas.local", "127.0.0.1"))
        assertThat(networkCell(NetworkTargetClass.LAN, NetworkAccessState.ALLOWLIST_ONLY, hosts).count).isEqualTo(2)
        assertThat(networkCell(NetworkTargetClass.LAN, NetworkAccessState.ALLOWLIST_ONLY, hosts).tone).isEqualTo(NetworkCellTone.LIMITED)

        val tailnet = NetworkTargetClass.TAILNET
        assertThat(networkCell(tailnet, NetworkAccessState.TAILNET_REQUIRED, snapshot(available = false)).meaning).isEqualTo(NetworkCellMeaning.TAILNET_MISSING)
        assertThat(networkCell(tailnet, NetworkAccessState.TAILNET_REQUIRED, snapshot()).meaning).isEqualTo(NetworkCellMeaning.TAILNET_DISABLED)
        assertThat(networkCell(tailnet, NetworkAccessState.TAILNET_REQUIRED, snapshot(enabled = true)).meaning).isEqualTo(NetworkCellMeaning.TAILNET_OFFLINE)
        val running = networkCell(tailnet, NetworkAccessState.TAILNET_REQUIRED, snapshot(enabled = true, running = true))
        assertThat(running.meaning).isEqualTo(NetworkCellMeaning.TAILNET_READY)
        assertThat(running.tone).isEqualTo(NetworkCellTone.OPEN)

        assertThat(networkCell(NetworkTargetClass.PUBLIC, NetworkAccessState.DENIED, empty).tone).isEqualTo(NetworkCellTone.CLOSED)
        assertThat(networkCell(NetworkTargetClass.PUBLIC, NetworkAccessState.UNRESTRICTED, empty).tone).isEqualTo(NetworkCellTone.OPEN)
        assertThat(networkCell(NetworkTargetClass.PUBLIC, NetworkAccessState.CONFIGURED_ONLY, empty).tone).isEqualTo(NetworkCellTone.LIMITED)
    }

    private fun snapshot(
        lanHosts: List<String> = emptyList(),
        available: Boolean = true,
        enabled: Boolean = false,
        running: Boolean = false,
    ) = AgentNetworkSnapshot(lanHosts, available, enabled, running, emptyList(), emptyList())

    private fun mount(alias: String, source: String, sessionId: String, unavailable: String? = null) =
        ExternalMount("id-$alias", alias, source, "src-$alias", sessionId, 1L, unavailable)

}
