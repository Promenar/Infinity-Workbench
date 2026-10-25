package com.promenar.nexara.ui.workspace

import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.storage.ExternalMountPaths
import com.promenar.nexara.data.termux.TermuxAvailability
import com.promenar.nexara.domain.model.ExecutionMode
import com.promenar.nexara.domain.network.AgentNetworkSnapshot
import com.promenar.nexara.domain.storage.ExternalMount
import com.promenar.nexara.ui.settings.TermuxEntryStatus
import com.promenar.nexara.ui.settings.termuxEntryStatus
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxUnavailableReason
import com.promenar.nexara.domain.tool.LinuxExecRouting

/**
 * “Agent 可达范围”面板的展示数据：当前会话的 Agent 此刻能碰到的工作区、挂载、执行后端、审批与网络。
 * 只描述现状，不参与任何授权判定；判定仍由各工具与执行策略完成。
 */
internal data class AgentReachState(
    val workspace: SessionWorkspaceUi = SessionWorkspaceUi.Loading,
    val mounts: List<ReachMount> = emptyList(),
    val backends: List<ReachBackend> = emptyList(),
    val executionMode: ExecutionMode = ExecutionMode.WORKSPACE,
    /** 网络访问总表；尚未取得时为 null。 */
    val network: AgentNetworkSnapshot? = null,
    /** 侧载渠道才有共享存储工作区与路径挂载。 */
    val sideload: Boolean = true,
)

internal enum class ReachMountSource { SAF, PATH }

internal data class ReachMount(
    val alias: String,
    val source: ReachMountSource,
    /** 设备路径来源可被命令以真实路径访问；系统授权来源只对文件工具可见。 */
    val commandAccessible: Boolean,
    val unavailable: Boolean,
)

internal enum class ReachBackendKind { FILES, BUILTIN, SANDBOX, LINUX, TERMUX, SSH }

internal enum class ReachBackendStatus {
    READY,
    /** 内置进程只有系统 shell，打包 Python 不可用（非 arm64 设备等）。 */
    SHELL_ONLY,
    /** 隔离脚本只有 JavaScript，打包 Python 不可用。 */
    JS_ONLY,
    DISABLED,
    NOT_INSTALLED,
    PERMISSION_REQUIRED,
    UNSUPPORTED_VERSION,
    /** SSH 未登记任何主机。 */
    NO_HOSTS,
    LOADING,
    INSTALLING,
    BROKEN,
    UNAVAILABLE,
}

/** [count] 只用于 SSH 的已登记主机数。 */
internal data class ReachBackend(val kind: ReachBackendKind, val status: ReachBackendStatus, val count: Int? = null, val destination: String? = null)

internal fun linuxReachStatus(state: LinuxRuntimeState, agentEnabled: Boolean): ReachBackendStatus = when (state) {
    is LinuxRuntimeState.Unavailable -> ReachBackendStatus.UNAVAILABLE
    is LinuxRuntimeState.NotInstalled -> ReachBackendStatus.NOT_INSTALLED
    is LinuxRuntimeState.Installing, is LinuxRuntimeState.Maintaining -> ReachBackendStatus.INSTALLING
    is LinuxRuntimeState.InstallInterrupted, is LinuxRuntimeState.Broken -> ReachBackendStatus.BROKEN
    is LinuxRuntimeState.Ready -> if (agentEnabled) ReachBackendStatus.READY else ReachBackendStatus.DISABLED
}

/** 工作区类型决定的保护与可见性，与执行层规则一一对应（见执行后端规格 §3.2、§4.4）。 */
internal data class WorkspaceTraits(
    /** 私有工作区由 Room 跟踪：文件工具删除进回收区、保留版本、命令结束后对账。 */
    val tracked: Boolean,
    /** 进入本地与 WebDAV 备份。 */
    val backedUp: Boolean,
    /** Termux 能直接看到这个目录（需 termux-setup-storage）。 */
    val termuxVisible: Boolean,
    /** 卸载 Nexara 或清除数据后仍保留。 */
    val survivesUninstall: Boolean,
)

internal fun workspaceTraits(kind: String?): WorkspaceTraits = when (kind) {
    WorkspaceEntity.KIND_PROJECT -> WorkspaceTraits(tracked = false, backedUp = false, termuxVisible = false, survivesUninstall = false)
    WorkspaceEntity.KIND_SHARED -> WorkspaceTraits(tracked = false, backedUp = false, termuxVisible = true, survivesUninstall = true)
    WorkspaceEntity.KIND_LINUX_HOME -> WorkspaceTraits(tracked = false, backedUp = false, termuxVisible = false, survivesUninstall = false)
    else -> WorkspaceTraits(tracked = true, backedUp = true, termuxVisible = false, survivesUninstall = false)
}

/** 不可用的目录工作区不给出特征，避免描述一个当前不能使用的位置。 */
internal val SessionWorkspaceUi.traits: WorkspaceTraits?
    get() = when (this) {
        SessionWorkspaceUi.Private -> workspaceTraits(null)
        is SessionWorkspaceUi.Directory -> workspaceTraits(kind)
        else -> null
    }

internal fun ExternalMount.toReach(): ReachMount = ReachMount(
    alias = alias,
    source = if (sourceType == "real_path") ReachMountSource.PATH else ReachMountSource.SAF,
    commandAccessible = commandAccessible,
    unavailable = unavailableReason != null,
)

/** Linux 就绪且开启时 shell_exec 在 Alpine 中运行，命令路径按 guest 视角解释，内置进程只剩 python_exec。 */
internal fun List<ReachBackend>.linuxRunsShell(): Boolean =
    any { it.kind == ReachBackendKind.LINUX && it.status == ReachBackendStatus.READY }

/** 用户级技能以保留别名 `skills` 出现在挂载列表中，面板单独说明，不作为普通挂载重复列出。 */
internal fun reachMounts(mounts: List<ExternalMount>): List<ReachMount> =
    mounts.filterNot { it.alias == ExternalMountPaths.SKILLS_ALIAS }.map(ExternalMount::toReach).sortedBy { it.alias }

/** 安装、版本与权限的判定沿用工具设置页入口的同一规则。 */
internal fun termuxReachStatus(enabled: Boolean, availability: TermuxAvailability?): ReachBackendStatus =
    if (!enabled) ReachBackendStatus.DISABLED else when (termuxEntryStatus(availability)) {
        TermuxEntryStatus.Loading -> ReachBackendStatus.LOADING
        TermuxEntryStatus.NotInstalled -> ReachBackendStatus.NOT_INSTALLED
        TermuxEntryStatus.UnsupportedVersion -> ReachBackendStatus.UNSUPPORTED_VERSION
        TermuxEntryStatus.PermissionRequired -> ReachBackendStatus.PERMISSION_REQUIRED
        TermuxEntryStatus.Available -> ReachBackendStatus.READY
    }

internal fun reachBackends(
    pythonAvailable: Boolean,
    termuxEnabled: Boolean,
    termuxAvailability: TermuxAvailability?,
    sshHostCount: Int?,
    linuxState: LinuxRuntimeState = LinuxExecRouting.state,
    linuxAgentEnabled: Boolean = LinuxExecRouting.agentEnabled,
): List<ReachBackend> = listOfNotNull(
    ReachBackend(ReachBackendKind.FILES, ReachBackendStatus.READY),
    ReachBackend(ReachBackendKind.BUILTIN, if (pythonAvailable) ReachBackendStatus.READY else ReachBackendStatus.SHELL_ONLY),
    ReachBackend(ReachBackendKind.SANDBOX, if (pythonAvailable) ReachBackendStatus.READY else ReachBackendStatus.JS_ONLY),
    // 渠道本身不提供 Linux 环境时不列出，避免让用户以为可以安装。
    ReachBackend(ReachBackendKind.LINUX, linuxReachStatus(linuxState, linuxAgentEnabled), destination = "linux_environment")
        .takeUnless { linuxState == LinuxRuntimeState.Unavailable(LinuxUnavailableReason.DISTRIBUTION) },
    ReachBackend(ReachBackendKind.TERMUX, termuxReachStatus(termuxEnabled, termuxAvailability)),
    ReachBackend(
        ReachBackendKind.SSH,
        when (sshHostCount) {
            null -> ReachBackendStatus.LOADING
            0 -> ReachBackendStatus.NO_HOSTS
            else -> ReachBackendStatus.READY
        },
        sshHostCount,
    ),
)
