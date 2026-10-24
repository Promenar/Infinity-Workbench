package com.promenar.nexara.data.linux

import com.promenar.nexara.domain.linux.LinuxProcessLaunch
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/** JNI 边界；会话管理测试使用内存实现，不加载 libtermux。 */
internal interface LinuxPty {
    val session: TerminalSession?
    val title: String?
    val exitCode: Int?
    fun start()
    suspend fun write(text: String)
    suspend fun terminate()
}

internal fun interface LinuxPtyFactory {
    fun create(launch: LinuxProcessLaunch, client: TerminalSessionClient): LinuxPty
}
