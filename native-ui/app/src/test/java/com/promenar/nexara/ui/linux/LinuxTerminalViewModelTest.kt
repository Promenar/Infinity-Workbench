package com.promenar.nexara.ui.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.jobs.BackgroundJob
import com.promenar.nexara.domain.jobs.BackgroundJobController
import com.promenar.nexara.domain.jobs.BackgroundJobExitAction
import com.promenar.nexara.domain.jobs.BackgroundJobRunner
import com.promenar.nexara.domain.jobs.BackgroundJobState
import com.promenar.nexara.domain.linux.LinuxDiagnostics
import com.promenar.nexara.domain.linux.LinuxEnvironmentInfo
import com.promenar.nexara.domain.linux.LinuxInstallOptions
import com.promenar.nexara.domain.linux.LinuxLaunchSpec
import com.promenar.nexara.domain.linux.LinuxPaths
import com.promenar.nexara.domain.linux.LinuxProcessLaunch
import com.promenar.nexara.domain.linux.LinuxRuntime
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxStorageUsage
import com.promenar.nexara.domain.linux.LinuxTerminalInfo
import com.promenar.nexara.domain.linux.LinuxTerminalLocation
import com.promenar.nexara.domain.linux.LinuxTerminalSessions
import com.promenar.nexara.ui.linux.terminal.LinuxTerminalRequest
import com.promenar.nexara.ui.linux.terminal.LinuxTerminalViewModel
import com.promenar.nexara.ui.linux.terminal.LiveTail
import com.promenar.nexara.ui.linux.terminal.TerminalExtraKey
import com.promenar.nexara.ui.linux.terminal.TerminalInputSink
import com.promenar.nexara.ui.linux.terminal.TerminalModifiers
import com.promenar.nexara.ui.linux.terminal.TerminalTab
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LinuxTerminalViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val ready = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "3.22", "aarch64", "5.4.0", 0L, null))

    private lateinit var terminals: FakeTerminals
    private lateinit var runtime: FakeRuntime
    private lateinit var jobs: FakeJobs
    private lateinit var sink: FakeSink
    private val tails = mutableMapOf<String, MutableStateFlow<LiveTail?>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        terminals = FakeTerminals()
        runtime = FakeRuntime(ready)
        jobs = FakeJobs()
        sink = FakeSink()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(request: LinuxTerminalRequest? = null) = LinuxTerminalViewModel(
        request = request,
        terminals = terminals,
        runtime = runtime,
        jobs = jobs,
        liveTail = { key -> tails.getOrPut(key) { MutableStateFlow(null) } },
        sessionTitle = { id -> "标题-$id" },
        input = sink,
    )

    @Test
    fun `没有会话也没有请求时在 root 新开一个终端`() {
        val vm = viewModel()

        assertThat(terminals.opened).containsExactly(LinuxTerminalLocation.Directory("/root") to null)
        val selected = vm.state.value.selectedTab as TerminalTab.Shell
        assertThat(selected.info.ordinal).isEqualTo(1)
    }

    @Test
    fun `有请求时新开会话并带初始命令`() {
        terminals.seed(LinuxTerminalLocation.Directory("/root"))
        val location = LinuxTerminalLocation.SessionWorkspace("chat-1")

        val vm = viewModel(LinuxTerminalRequest(location, "make test"))

        assertThat(terminals.opened).containsExactly(location to "make test")
        assertThat((vm.state.value.selectedTab as TerminalTab.Shell).info.location).isEqualTo(location)
        assertThat(vm.state.value.tabs).hasSize(2)
        assertThat(vm.state.value.sessionTitles).containsEntry("chat-1", "标题-chat-1")
    }

    @Test
    fun `没有请求时展示已有会话并选中最新的一个，不再新开`() {
        terminals.seed(LinuxTerminalLocation.Directory("/root"))
        val second = terminals.seed(LinuxTerminalLocation.Directory("/tmp"))

        val vm = viewModel()

        assertThat(terminals.opened).isEmpty()
        assertThat(vm.state.value.selectedKey).isEqualTo(TerminalTab.shellKey(second))
    }

    @Test
    fun `环境未就绪时不打开，就绪后再执行请求`() {
        runtime.state.value = LinuxRuntimeState.NotInstalled(1L)
        val vm = viewModel(LinuxTerminalRequest(LinuxTerminalLocation.Directory("/srv"), null))

        assertThat(terminals.opened).isEmpty()
        assertThat(vm.state.value.ready).isFalse()

        runtime.state.value = ready
        assertThat(terminals.opened).containsExactly(LinuxTerminalLocation.Directory("/srv") to null)
        // 状态再次变化不会重复打开。
        runtime.state.value = LinuxRuntimeState.NotInstalled(1L)
        runtime.state.value = ready
        assertThat(terminals.opened).hasSize(1)
    }

    @Test
    fun `打开失败显示错误，重试后成功`() {
        terminals.failNext = true
        val vm = viewModel()

        assertThat(vm.state.value.openFailed).isTrue()
        assertThat(vm.state.value.opening).isFalse()
        assertThat(vm.state.value.tabs).isEmpty()

        vm.retryOpen()
        assertThat(vm.state.value.openFailed).isFalse()
        assertThat(vm.state.value.tabs).hasSize(1)
    }

    @Test
    fun `新建沿用当前终端的位置`() {
        val location = LinuxTerminalLocation.SessionWorkspace("chat-9")
        val vm = viewModel(LinuxTerminalRequest(location))

        vm.openNew()

        assertThat(terminals.opened.map { it.first }).containsExactly(location, location)
        assertThat((vm.state.value.selectedTab as TerminalTab.Shell).info.ordinal).isEqualTo(2)
    }

    @Test
    fun `关闭选中页签时结束会话并选中相邻页签`() {
        val first = terminals.seed(LinuxTerminalLocation.Directory("/a"))
        val second = terminals.seed(LinuxTerminalLocation.Directory("/b"))
        val vm = viewModel()
        vm.select(TerminalTab.shellKey(first))

        vm.close(TerminalTab.shellKey(first))

        assertThat(terminals.closed).containsExactly(first)
        assertThat(vm.state.value.selectedKey).isEqualTo(TerminalTab.shellKey(second))
        assertThat(vm.state.value.tabs.map { it.key }).containsExactly(TerminalTab.shellKey(second))
    }

    @Test
    fun `修饰键一次性作用于下一次输入`() {
        val vm = viewModel()
        val id = (vm.state.value.selectedTab as TerminalTab.Shell).info.id

        vm.toggleCtrl()
        assertThat(vm.state.value.modifiers).isEqualTo(TerminalModifiers(ctrl = true))
        vm.sendKey(TerminalExtraKey.UP)
        assertThat(sink.written.last()).isEqualTo(id to "\u001B[1;5A")
        assertThat(vm.state.value.modifiers).isEqualTo(TerminalModifiers.NONE)

        vm.toggleAlt()
        vm.toggleAlt()
        assertThat(vm.state.value.modifiers.any).isFalse()

        // 系统键盘输入读取即消耗。
        vm.toggleCtrl()
        vm.toggleAlt()
        assertThat(vm.consumeCtrl()).isTrue()
        assertThat(vm.consumeCtrl()).isFalse()
        assertThat(vm.consumeAlt()).isTrue()
        assertThat(vm.state.value.modifiers).isEqualTo(TerminalModifiers.NONE)
    }

    @Test
    fun `方向键按会话的光标键模式编码，已退出的会话不再接收按键`() {
        val vm = viewModel()
        val id = (vm.state.value.selectedTab as TerminalTab.Shell).info.id
        sink.applicationMode = true

        vm.sendKey(TerminalExtraKey.LEFT)
        assertThat(sink.written.single()).isEqualTo(id to "\u001BOD")

        terminals.exit(id, 0)
        vm.sendKey(TerminalExtraKey.ESC)
        assertThat(sink.written).hasSize(1)
        assertThat((vm.state.value.selectedTab as TerminalTab.Shell).info.exitCode).isEqualTo(0)
    }

    @Test
    fun `只读任务页签只显示相关会话中运行的任务`() {
        jobs.flow.value = listOf(
            job("j1", "chat-1", startedAt = 10),
            job("j2", "chat-1", startedAt = 20),
            job("other", "chat-2", startedAt = 15),
            job("done", "chat-1", startedAt = 5, state = BackgroundJobState.EXITED),
        )

        val vm = viewModel(LinuxTerminalRequest(LinuxTerminalLocation.SessionWorkspace("chat-1")))

        val jobTabs = vm.state.value.tabs.filterIsInstance<TerminalTab.AgentJob>()
        assertThat(jobTabs.map { it.job.id }).containsExactly("j1", "j2").inOrder()
        // 编号按该会话全部任务的启动顺序：done 是 #1。
        assertThat(jobTabs.map { it.ordinal }).containsExactly(2, 3).inOrder()
    }

    @Test
    fun `选中任务页签时订阅输出尾部，任务结束后保留最后内容直到切走`() {
        jobs.flow.value = listOf(job("j1", "chat-1", startedAt = 1))
        val vm = viewModel(LinuxTerminalRequest(LinuxTerminalLocation.SessionWorkspace("chat-1")))
        val shellKey = vm.state.value.selectedKey!!

        vm.select(TerminalTab.jobKey("j1"))
        tails.getValue("job:j1").value = LiveTail("step 1\n", truncated = false)
        assertThat(vm.state.value.jobTails["j1"]).isEqualTo(LiveTail("step 1\n", false))
        assertThat(vm.state.value.modifiers).isEqualTo(TerminalModifiers.NONE)

        jobs.flow.value = listOf(job("j1", "chat-1", startedAt = 1, state = BackgroundJobState.EXITED))
        tails.getValue("job:j1").value = null
        assertThat(vm.state.value.selectedTab).isInstanceOf(TerminalTab.AgentJob::class.java)
        assertThat(vm.state.value.jobTails["j1"]?.text).isEqualTo("step 1\n")

        vm.select(shellKey)
        assertThat(vm.state.value.tabs.filterIsInstance<TerminalTab.AgentJob>()).isEmpty()
    }

    @Test
    fun `关闭运行中任务页签只是不再显示`() {
        jobs.flow.value = listOf(job("j1", "chat-1", startedAt = 1))
        val vm = viewModel(LinuxTerminalRequest(LinuxTerminalLocation.SessionWorkspace("chat-1")))

        vm.close(TerminalTab.jobKey("j1"))
        jobs.flow.update { it.toList() }

        assertThat(vm.state.value.tabs.filterIsInstance<TerminalTab.AgentJob>()).isEmpty()
        assertThat(terminals.closed).isEmpty()
    }

    private fun job(id: String, session: String, startedAt: Long, state: BackgroundJobState = BackgroundJobState.RUNNING) = BackgroundJob(
        id = id,
        sessionId = session,
        runner = BackgroundJobRunner.SHELL,
        summary = "run $id",
        state = state,
        exitCode = if (state == BackgroundJobState.RUNNING) null else 0,
        startedAtMillis = startedAt,
        endedAtMillis = null,
        maxRuntimeMinutes = 120,
        onExit = BackgroundJobExitAction.NOTIFY,
        logWorkspacePath = "jobs/$id.log",
        liveOutputKey = "job:$id",
    )

    private class FakeTerminals : LinuxTerminalSessions {
        private val flow = MutableStateFlow<List<LinuxTerminalInfo>>(emptyList())
        override val sessions: StateFlow<List<LinuxTerminalInfo>> = flow
        val opened = mutableListOf<Pair<LinuxTerminalLocation, String?>>()
        val closed = mutableListOf<String>()
        var failNext = false
        private var counter = 0

        fun seed(location: LinuxTerminalLocation): String {
            counter++
            val id = "t$counter"
            flow.update { it + LinuxTerminalInfo(id, counter, null, location, 0L, running = true) }
            return id
        }

        fun exit(id: String, code: Int) = flow.update { list ->
            list.map { if (it.id == id) it.copy(running = false, exitCode = code) else it }
        }

        override suspend fun open(location: LinuxTerminalLocation, initialCommand: String?): String {
            if (failNext) {
                failNext = false
                throw IllegalStateException("not ready")
            }
            opened += location to initialCommand
            return seed(location)
        }

        override fun session(id: String): TerminalSession? = null
        override fun attachClient(id: String, client: TerminalSessionClient?) = Unit
        override fun close(id: String) {
            closed += id
            flow.update { list -> list.filterNot { it.id == id } }
        }
    }

    private class FakeRuntime(initial: LinuxRuntimeState) : LinuxRuntime {
        override val state = MutableStateFlow(initial)
        override val agentEnabled: StateFlow<Boolean> = MutableStateFlow(true)
        override suspend fun setAgentEnabled(enabled: Boolean) = Unit
        override fun startInstall(options: LinuxInstallOptions) = Unit
        override fun cancelInstall() = Unit
        override suspend fun storageUsage() = LinuxStorageUsage(0, 0, 0, 0)
        override suspend fun clearDownloadCache() = 0L
        override suspend fun runDiagnostics(): LinuxDiagnostics = error("unused")
        override suspend fun reset() = Unit
        override suspend fun uninstall() = Unit
        override suspend fun prepareLaunch(spec: LinuxLaunchSpec): LinuxProcessLaunch = error("unused")
        override val paths = LinuxPaths(File("rootfs"), File("rootfs/root"))
    }

    private class FakeJobs : BackgroundJobController {
        val flow = MutableStateFlow<List<BackgroundJob>>(emptyList())
        override fun observe(sessionId: String?): Flow<List<BackgroundJob>> = flow
        override suspend fun stop(jobId: String) = false
    }

    private class FakeSink : TerminalInputSink {
        val written = mutableListOf<Pair<String, String>>()
        var applicationMode = false
        override fun write(sessionId: String, text: String) {
            written += sessionId to text
        }
        override fun cursorKeysApplicationMode(sessionId: String) = applicationMode
    }
}
