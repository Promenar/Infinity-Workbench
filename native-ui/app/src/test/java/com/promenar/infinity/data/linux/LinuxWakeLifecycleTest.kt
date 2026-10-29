package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.jobs.*
import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.domain.trigger.*
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class LinuxWakeLifecycleTest {
    @TempDir lateinit var root: Path
    private val ready = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "test", "aarch64", "test", 0, null))

    private class Jobs : BackgroundJobController {
        val state = MutableStateFlow<List<BackgroundJob>>(emptyList())
        var subscriptions = 0
        override fun observe(sessionId: String?) = state.also { subscriptions++ }
        override suspend fun stop(jobId: String) = false
    }

    private class Runtime(root: File) : LinuxRuntime by UnavailableLinuxRuntime(
        LinuxUnavailableReason.DISTRIBUTION, LinuxPaths(root, root),
    ) {
        override val state = MutableStateFlow<LinuxRuntimeState>(LinuxRuntimeState.NotInstalled(0))
    }

    private fun job(state: BackgroundJobState) = BackgroundJob("j", "s", BackgroundJobRunner.LINUX, "命令", state,
        null, 0, null, 10, BackgroundJobExitAction.NONE, null, "output")

    @Test fun `Ready后才恢复且任务开始结束均补扫重复启动不重复订阅`() = runTest {
        val runtime = Runtime(root.toFile()); val jobs = Jobs(); val requests = mutableListOf<SystemTurnRequest>()
        val box = root.resolve("${LinuxWakeLimits.HOST_DIRECTORY}/${LinuxWakeInbox.hash("s")}/box").toFile().apply { mkdirs() }
        File(box.parentFile, "session").writeText("s")
        File(box, "1.json").writeText("{\"v\":1,\"key\":\"startup\"}")
        val dispatch = LinuxWakeDispatch(backgroundScope, { object : SystemTurnDispatcher {
            override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome {
                requests += request; return SystemTurnOutcome.Completed
            }
        } }, { testScheduler.currentTime })
        val inbox = LinuxWakeInbox(root.toFile(), backgroundScope, dispatch, LinuxWakeWatcher { _, _ -> AutoCloseable {} },
            { true }, StandardTestDispatcher(testScheduler))
        val lifecycle = LinuxWakeLifecycle(runtime, jobs, inbox, backgroundScope, StandardTestDispatcher(testScheduler))
        lifecycle.start(); lifecycle.start(); runCurrent()
        assertTrue(requests.isEmpty()); assertEquals(0, jobs.subscriptions)
        runtime.state.value = ready; runCurrent()
        assertEquals("wake:startup", requests.single().sourceId); assertEquals(1, jobs.subscriptions)
        advanceTimeBy(LinuxWakeLimits.MIN_DISPATCH_INTERVAL)
        File(box, "2.json").writeText("{\"v\":1,\"key\":\"start\"}")
        jobs.state.value = listOf(job(BackgroundJobState.RUNNING)); runCurrent()
        assertEquals("wake:start", requests.last().sourceId)
        advanceTimeBy(LinuxWakeLimits.MIN_DISPATCH_INTERVAL)
        File(box, "3.json").writeText("{\"v\":1,\"key\":\"end\"}")
        jobs.state.value = listOf(job(BackgroundJobState.EXITED)); runCurrent()
        assertEquals("wake:end", requests.last().sourceId); assertEquals(3, requests.size)
    }

    @Test fun `应用作用域结束关闭监听且不能再准备收件箱`() = runTest {
        val application = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var closed = false
        val inbox = LinuxWakeInbox(root.toFile(), application, LinuxWakeDispatch(application, { null }),
            LinuxWakeWatcher { _, _ -> AutoCloseable { closed = true } }, { true }, StandardTestDispatcher(testScheduler))
        inbox.prepare("s"); runCurrent()
        application.cancel(); runCurrent()
        assertTrue(closed)
        assertTrue(runCatching { inbox.prepare("s") }.exceptionOrNull() is CancellationException)
    }
}
