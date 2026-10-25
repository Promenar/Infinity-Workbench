package com.promenar.nexara.domain.tool

import com.promenar.nexara.domain.linux.LinuxRuntime
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxUnavailableReason

/** 应用运行时可用性查询；命令路由由调用所属会话的运行模式决定。 */
object LinuxExecRouting {
    @Volatile
    var runtime: LinuxRuntime? = null

    val state: LinuxRuntimeState
        get() = runtime?.state?.value ?: LinuxRuntimeState.Unavailable(LinuxUnavailableReason.DISTRIBUTION)
    val agentEnabled: Boolean get() = runtime?.agentEnabled?.value == true
    val available: Boolean get() = state is LinuxRuntimeState.Ready

}
