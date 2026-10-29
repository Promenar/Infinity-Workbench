package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.jobs.BackgroundJobController
import com.promenar.infinity.domain.jobs.BackgroundJobState
import com.promenar.infinity.domain.linux.LinuxRuntime
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import java.util.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** 启动恢复在数据写入解禁后调用；只在 Linux Ready 后补扫并订阅任务状态。 */
class LinuxWakeLifecycle(
    private val runtime: LinuxRuntime,
    private val jobs: BackgroundJobController,
    private val inbox: LinuxWakeInbox,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var monitoring: Job? = null

    @Synchronized fun start() {
        if (monitoring?.isActive == true) return
        monitoring = scope.launch(ioDispatcher) {
            try {
                val state = runtime.state.first { it is LinuxRuntimeState.Ready || it is LinuxRuntimeState.Unavailable }
                if (state is LinuxRuntimeState.Unavailable) return@launch
                inbox.recover()
                var previous = emptyMap<String, BackgroundJobState>()
                jobs.observe(null).collect { current ->
                    current.filter { previous[it.id] != it.state }.map { it.sessionId }.distinct().forEach { inbox.scan(it) }
                    previous = current.associate { it.id to it.state }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { Logger.getLogger("LinuxWake").warning("脚本唤醒生命周期补扫失败") }
        }
    }
}
