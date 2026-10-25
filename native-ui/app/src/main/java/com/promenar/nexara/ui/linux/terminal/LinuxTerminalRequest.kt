package com.promenar.nexara.ui.linux.terminal

import com.promenar.nexara.domain.linux.LinuxTerminalLocation

/**
 * 打开终端页的请求：非 null 时进入页面即新开一个会话；[initialCommand] 非 null 时会话启动后自动输入并回车。
 *
 * 例如“在终端重跑”传 `LinuxTerminalRequest(LinuxTerminalLocation.SessionWorkspace(sessionId), command)`。
 */
data class LinuxTerminalRequest(
    val location: LinuxTerminalLocation,
    val initialCommand: String? = null,
)
