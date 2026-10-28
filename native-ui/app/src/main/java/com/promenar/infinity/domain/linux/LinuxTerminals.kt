package com.promenar.infinity.domain.linux

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.StateFlow

/**
 * 本地 Linux 终端会话（真实 PTY）。会话属于应用进程，页面离开或旋转不结束；有会话运行时持有前台服务。
 * 终端输入由用户直接执行，不经过 Agent 审批，也不计入会话。
 */
interface LinuxTerminalSessions {
    val sessions: StateFlow<List<LinuxTerminalInfo>>

    /** 新开会话并返回 id。[initialCommand] 非 null 时启动后自动输入并回车。 */
    suspend fun open(location: LinuxTerminalLocation, initialCommand: String? = null): String

    /** 供 TerminalView 绑定；会话已结束并被移除时为 null。 */
    fun session(id: String): TerminalSession?

    /** 切换界面回调对象（TerminalView 重建时调用）；传 null 解绑。 */
    fun attachClient(id: String, client: TerminalSessionClient?)

    fun close(id: String)
}

sealed interface LinuxTerminalLocation {
    /** 绑定会话工作区到 `/workspace` 并从这里开始。 */
    data class SessionWorkspace(val sessionId: String) : LinuxTerminalLocation

    /** 从容器内某个目录开始（默认 `/root`），不绑定工作区。 */
    data class Directory(val path: String = "/root") : LinuxTerminalLocation
}

data class LinuxTerminalInfo(
    val id: String,
    /** 序号从 1 开始，用于“终端 1”这类标签。 */
    val ordinal: Int,
    val title: String?,
    val location: LinuxTerminalLocation,
    val startedAtMs: Long,
    val running: Boolean,
    val exitCode: Int? = null,
)
