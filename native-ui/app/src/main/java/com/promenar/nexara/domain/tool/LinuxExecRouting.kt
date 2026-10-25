package com.promenar.nexara.domain.tool

import com.promenar.nexara.domain.linux.LinuxRuntime
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxUnavailableReason

/** 旧工具与提示词入口共享应用注入的运行时；判定函数本身不读取可变状态。 */
object LinuxExecRouting {
    @Volatile
    var runtime: LinuxRuntime? = null

    val state: LinuxRuntimeState
        get() = runtime?.state?.value ?: LinuxRuntimeState.Unavailable(LinuxUnavailableReason.DISTRIBUTION)
    val agentEnabled: Boolean get() = runtime?.agentEnabled?.value == true
    val agentUsesLinux: Boolean get() = usesLinux(state, agentEnabled)

    fun usesLinux(state: LinuxRuntimeState, agentEnabled: Boolean): Boolean =
        agentEnabled && state is LinuxRuntimeState.Ready
}
