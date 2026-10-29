package com.promenar.infinity.data.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.exec.ExecForegroundState
import com.promenar.infinity.data.exec.ExecHeld
import com.promenar.infinity.data.exec.ExecKeepAlive
import com.promenar.infinity.data.exec.ExecRunInfo
import com.promenar.infinity.data.exec.ExecRunKind
import com.promenar.infinity.domain.linux.*
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinuxTerminalSessionsTest {
    @Test fun `会话终端透传唤醒收件箱而目录终端不创建`() = runTest {
        val f = fixture()
        f.wakeBox = File("/wake-box")
        f.manager.open(LinuxTerminalLocation.SessionWorkspace("chat"))
        assertThat(f.runtime.specs.last().wakeInbox).isEqualTo(f.wakeBox)
        assertThat(f.wakeSessions).containsExactly("chat")
        f.manager.open(LinuxTerminalLocation.Directory())
        assertThat(f.runtime.specs.last().wakeInbox).isNull()
        assertThat(f.wakeSessions).containsExactly("chat")
    }

    @org.junit.After fun resetBarrier() { com.promenar.infinity.startup.StartupWriteBarrier.resetForTesting() }


    @Test fun `guest绝对符号链接工作目录按rootfs语义检查隔离`() = runTest {
        val f = fixture()
        val directory = java.nio.file.Files.createTempDirectory("linux-terminal-isolation").toFile().canonicalFile
        try {
            val home = File(directory, "root").apply { mkdirs() }
            java.nio.file.Files.createSymbolicLink(File(directory, "alias").toPath(), java.nio.file.Path.of("/root"))
            f.runtime.paths = LinuxPaths(directory, home)
            val manager = com.promenar.infinity.startup.StartupIssueManager()
            com.promenar.infinity.startup.StartupWriteBarrier.install(manager)
            manager.bindRoot("root", home.path, "s")
            manager.recover(com.promenar.infinity.startup.StartupStep.WORKSPACE_JOURNAL,
                com.promenar.infinity.startup.StartupIssueScope(com.promenar.infinity.startup.StartupScopeKind.WORKSPACE_ROOT, "root", "工作区")) { error("冲突") }
            assertThat(runCatching { f.manager.open(LinuxTerminalLocation.Directory("/alias")) }.exceptionOrNull())
                .isInstanceOf(com.promenar.infinity.startup.StartupIsolationException::class.java)
            assertThat(f.runtime.specs).isEmpty()
            assertThat(f.ptys).isEmpty()
        } finally { directory.deleteRecursively() }
    }

    @Test fun `隔离会话或物理工作目录时不对账不生成启动规格且不启动PTY`() = runTest {
        val f = fixture()
        val manager = com.promenar.infinity.startup.StartupIssueManager()
        com.promenar.infinity.startup.StartupWriteBarrier.install(manager)
        manager.bindRoot("root", "/workspace-host", "other")
        manager.recover(com.promenar.infinity.startup.StartupStep.WORKSPACE_JOURNAL,
            com.promenar.infinity.startup.StartupIssueScope(com.promenar.infinity.startup.StartupScopeKind.WORKSPACE_ROOT, "root", "工作区")) { error("冲突") }
        assertThat(runCatching { f.manager.open(LinuxTerminalLocation.SessionWorkspace("chat")) }.exceptionOrNull())
            .isInstanceOf(com.promenar.infinity.startup.StartupIsolationException::class.java)
        assertThat(f.reconciled).isEmpty()
        assertThat(f.runtime.specs).isEmpty()
        assertThat(f.ptys).isEmpty()
        manager.recover(com.promenar.infinity.startup.StartupStep.SESSION_JOURNAL,
            com.promenar.infinity.startup.StartupIssueScope(com.promenar.infinity.startup.StartupScopeKind.SESSION, "blocked", "会话")) { error("冲突") }
        assertThat(runCatching { f.manager.open(LinuxTerminalLocation.SessionWorkspace("blocked")) }.exceptionOrNull())
            .isInstanceOf(com.promenar.infinity.startup.StartupIsolationException::class.java)
        assertThat(f.reconciled).isEmpty()
    }

    @Test fun `无效目录在生成启动参数前被拒绝`() = runTest {
        val f = fixture()
        for (path in listOf("relative", "", "/tmp\u0000bad")) {
            assertThat(runCatching { f.manager.open(LinuxTerminalLocation.Directory(path)) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(f.runtime.specs).isEmpty()
        assertThat(f.ptys).isEmpty()
    }

    @Test fun `NUL命令在创建PTY前被拒绝`() = runTest {
        val f = fixture()
        assertThat(runCatching { f.manager.open(LinuxTerminalLocation.Directory(), "echo\u0000bad") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(f.runtime.specs).isEmpty()
        assertThat(f.keep.runs).isEmpty()
    }

    @Test fun `工作区映射交互登录规格且保留宿主启动信息`() = runTest {
        val f = fixture()
        val id = f.manager.open(LinuxTerminalLocation.SessionWorkspace("chat"))
        assertThat(f.resolved).containsExactly("chat")
        assertThat(f.runtime.specs.single()).isEqualTo(LinuxLaunchSpec(emptyList(), File("/workspace-host"), workingDirectory = "/workspace", sessionHome = File("/private-chat")))
        assertThat(f.launches.single()).isEqualTo(f.runtime.launch)
        assertThat(f.manager.sessions.value.single().id).isEqualTo(id)
        assertThat(f.ptys.single().starts).isEqualTo(1)
        assertThat(f.keep.runs.values.single().kind).isEqualTo(ExecRunKind.TERMINAL)
    }

    @Test fun `会话终端启动和退出均对账且home绑定不受主工作区影响`() = runTest {
        val f = fixture()
        f.manager.open(LinuxTerminalLocation.SessionWorkspace("chat"))
        assertThat(f.reconciled).containsExactly("chat")
        assertThat(f.runtime.specs.single().sessionHome).isEqualTo(File("/private-chat"))
        f.ptys.single().finish(0)
        runCurrent()
        assertThat(f.reconciled).containsExactly("chat", "chat").inOrder()
        f.manager.open(LinuxTerminalLocation.Directory("/root"))
        f.ptys.last().finish(0)
        runCurrent()
        assertThat(f.reconciled).hasSize(2)
    }

    @Test fun `目录不解析工作区且序号单调递增退出保留直到关闭`() = runTest {
        val f = fixture()
        val first = f.manager.open(LinuxTerminalLocation.Directory("/tmp"))
        val second = f.manager.open(LinuxTerminalLocation.Directory())
        f.ptys[0].finish(7)
        runCurrent()
        assertThat(f.manager.sessions.value.map { it.ordinal }).containsExactly(1, 2).inOrder()
        assertThat(f.manager.sessions.value[0].running).isFalse()
        assertThat(f.manager.sessions.value[0].exitCode).isEqualTo(7)
        assertThat(f.keep.runs.keys).containsExactly(second)
        f.manager.close(first)
        val third = f.manager.open(LinuxTerminalLocation.Directory())
        assertThat(f.manager.sessions.value.map { it.ordinal }).containsExactly(2, 3).inOrder()
        assertThat(f.manager.sessions.value.last().id).isEqualTo(third)
        assertThat(f.runtime.specs.first()).isEqualTo(LinuxLaunchSpec(emptyList(), workingDirectory = "/tmp"))
        assertThat(f.resolved).isEmpty()
    }

    @Test fun `工作区不可用或解析异常时带原因失败且不启动和保活`() = runTest {
        val f = fixture()
        f.workspace = null
        val missing = runCatching { f.manager.open(LinuxTerminalLocation.SessionWorkspace("missing")) }.exceptionOrNull()
        assertThat(missing).isInstanceOf(IllegalStateException::class.java)
        assertThat(missing?.message).contains("missing")
        f.resolveFailure = IllegalArgumentException("workspace unavailable")
        val failed = runCatching { f.manager.open(LinuxTerminalLocation.SessionWorkspace("chat")) }.exceptionOrNull()
        // 协程栈恢复可能复制外层异常，原始解析失败必须仍在原因链中。
        assertThat(generateSequence(failed) { it.cause }.any { it === f.resolveFailure }).isTrue()
        assertThat(f.ptys).isEmpty()
        assertThat(f.keep.runs).isEmpty()
    }

    @Test fun `启动失败释放保活并移除半开会话`() = runTest {
        val f = fixture()
        f.failStart = true
        assertThat(runCatching { f.manager.open(LinuxTerminalLocation.Directory()) }.isFailure).isTrue()
        runCurrent()
        assertThat(f.manager.sessions.value).isEmpty()
        assertThat(f.keep.runs).isEmpty()
    }

    @Test fun `关闭幂等且通知只停止对应会话最后退出释放保活`() = runTest {
        val f = fixture()
        val first = f.manager.open(LinuxTerminalLocation.Directory())
        val second = f.manager.open(LinuxTerminalLocation.Directory())
        f.keep.stops.getValue(first).invoke()
        f.manager.close(first)
        runCurrent()
        assertThat(f.ptys[0].terminations).isEqualTo(1)
        assertThat(f.ptys[1].terminations).isEqualTo(0)
        assertThat(f.keep.runs.keys).containsExactly(second)
        f.ptys[1].finish(0)
        runCurrent()
        assertThat(f.keep.runs).isEmpty()
        assertThat(f.manager.sessions.value.single().running).isFalse()
    }

    @Test fun `环境失效停止全部且保留退出码`() = runTest {
        val f = fixture()
        repeat(2) { f.manager.open(LinuxTerminalLocation.Directory()) }
        f.runtime.state.value = LinuxRuntimeState.Maintaining(LinuxMaintenance.RESETTING)
        runCurrent()
        assertThat(f.ptys.map { it.terminations }).containsExactly(1, 1)
        assertThat(f.manager.sessions.value.map { it.running }).containsExactly(false, false)
        assertThat(f.keep.runs).isEmpty()
        assertThat(runCatching { f.manager.open(LinuxTerminalLocation.Directory()) }.exceptionOrNull())
            .isInstanceOf(LinuxUnavailableException::class.java)
    }

    @Test fun `准备启动期间环境失效不得创建PTY`() = runTest {
        val f = fixture()
        val gate = CompletableDeferred<Unit>()
        f.runtime.prepareGate = gate
        val opening = async { runCatching { f.manager.open(LinuxTerminalLocation.Directory()) } }
        runCurrent()
        f.runtime.state.value = LinuxRuntimeState.NotInstalled(0)
        runCurrent()
        gate.complete(Unit)
        assertThat(opening.await().isFailure).isTrue()
        assertThat(f.ptys).isEmpty()
    }

    @Test fun `初始命令等待唯一shell握手且只发送一次`() = runTest {
        val f = fixture()
        val opening = async { f.manager.open(LinuxTerminalLocation.Directory(), "echo hello") }
        runCurrent()
        val pty = f.ptys.single()
        assertThat(opening.isCompleted).isFalse()
        assertThat(f.manager.sessions.value).isEmpty()
        assertThat(pty.writes).hasSize(1)
        assertThat(pty.writes.single()).doesNotContain("echo hello")
        // 握手输出同时清屏与清空滚动区，界面接管时看不到握手命令的回显。
        assertThat(pty.writes.single()).endsWith("\\033[H\\033[2J\\033[3J'\r")
        pty.client.onTextChanged(null)
        pty.emitTitle("ordinary prompt")
        runCurrent()
        assertThat(pty.writes).hasSize(1)
        val marker = pty.writes.first().substringAfter("0;").substringBefore("\\007")
        val id = marker.removePrefix("infinity-ready-")
        assertThat(f.manager.session(id)).isNull()
        pty.emitTitle(marker)
        pty.emitTitle(marker)
        runCurrent()
        assertThat(opening.await()).isEqualTo(id)
        assertThat(f.manager.sessions.value.single().id).isEqualTo(id)
        assertThat(pty.writes).containsExactly(pty.writes.first(), "echo hello\r").inOrder()
    }

    @Test fun `握手前退出会使打开失败并释放所有资源`() = runTest {
        val f = fixture()
        val opening = async { runCatching { f.manager.open(LinuxTerminalLocation.Directory(), "echo hello") } }
        runCurrent()
        f.ptys.single().finish(1)
        assertThat(opening.await().isFailure).isTrue()
        runCurrent()
        assertThat(f.keep.runs).isEmpty()
        assertThat(f.manager.sessions.value).isEmpty()
    }

    @Test fun `握手超时关闭半开会话并释放保活`() = runTest {
        val f = fixture()
        val opening = async { runCatching { f.manager.open(LinuxTerminalLocation.Directory(), "echo hello") } }
        assertThat(opening.await().isFailure).isTrue()
        runCurrent()
        assertThat(f.ptys.single().terminations).isEqualTo(1)
        assertThat(f.keep.runs).isEmpty()
        assertThat(f.manager.sessions.value).isEmpty()
    }

    @Test fun `初始命令写入失败结束会话且不返回可绑定终端`() = runTest {
        val f = fixture()
        val opening = async { runCatching { f.manager.open(LinuxTerminalLocation.Directory(), "echo hello") } }
        runCurrent()
        val pty = f.ptys.single()
        pty.failWrite = true
        pty.emitTitle(pty.writes.first().substringAfter("0;").substringBefore("\\007"))
        assertThat(opening.await().isFailure).isTrue()
        runCurrent()
        assertThat(f.keep.runs).isEmpty()
        assertThat(f.manager.sessions.value).isEmpty()
    }

    @Test fun `初始命令立即退出仍保留退出记录`() = runTest {
        val f = fixture()
        val opening = async { f.manager.open(LinuxTerminalLocation.Directory(), "exit 0") }
        runCurrent()
        val pty = f.ptys.single()
        pty.finishOnCommand = true
        pty.emitTitle(pty.writes.first().substringAfter("0;").substringBefore("\\007"))
        val id = opening.await()
        runCurrent()
        assertThat(f.manager.sessions.value.single().id).isEqualTo(id)
        assertThat(f.manager.sessions.value.single().running).isFalse()
        assertThat(f.manager.sessions.value.single().exitCode).isEqualTo(0)
        assertThat(f.keep.runs).isEmpty()
    }

    @Test fun `握手后尚未写入便退出时立即失败`() = runTest {
        val f = fixture()
        val opening = async { runCatching { f.manager.open(LinuxTerminalLocation.Directory(), "echo hello") } }
        runCurrent()
        val pty = f.ptys.single()
        val started = testScheduler.currentTime
        pty.emitTitle(pty.writes.first().substringAfter("0;").substringBefore("\\007"))
        pty.finish(1)
        assertThat(opening.await().isFailure).isTrue()
        assertThat(testScheduler.currentTime).isEqualTo(started)
        runCurrent()
        assertThat(f.keep.runs).isEmpty()
    }

    @Test fun `回调切换和解绑不影响内部退出及标题状态`() = runTest {
        val f = fixture()
        val id = f.manager.open(LinuxTerminalLocation.Directory())
        val first = Client()
        val second = Client()
        f.manager.attachClient(id, first)
        f.ptys.single().emitTitle("one")
        f.manager.attachClient(id, second)
        f.ptys.single().emitTitle("two")
        f.manager.attachClient(id, null)
        f.ptys.single().emitTitle("three")
        f.ptys.single().finish(3)
        runCurrent()
        assertThat(first.titles).isEqualTo(1)
        assertThat(second.titles).isEqualTo(1)
        assertThat(f.manager.sessions.value.single().title).isEqualTo("three")
        assertThat(f.manager.sessions.value.single().exitCode).isEqualTo(3)
        assertThat(f.keep.runs).isEmpty()
    }

    @Test fun `关闭后等待进程清理完成才释放保活`() = runTest {
        val f = fixture()
        val id = f.manager.open(LinuxTerminalLocation.Directory())
        val gate = CompletableDeferred<Unit>()
        f.ptys.single().terminationGate = gate
        f.manager.close(id)
        runCurrent()
        assertThat(f.manager.sessions.value).isEmpty()
        assertThat(f.keep.runs.keys).containsExactly(id)
        gate.complete(Unit)
        runCurrent()
        assertThat(f.keep.runs).isEmpty()
    }

    @Test fun `保活尚未建立时取消打开不会启动PTY或泄漏登记`() = runTest {
        val f = fixture()
        val gate = CompletableDeferred<Unit>()
        f.keep.acquireGate = gate
        val opening = async { f.manager.open(LinuxTerminalLocation.Directory()) }
        runCurrent()
        assertThat(f.ptys.single().starts).isEqualTo(0)
        opening.cancelAndJoin()
        gate.complete(Unit)
        runCurrent()
        assertThat(f.ptys.single().starts).isEqualTo(0)
        assertThat(f.ptys.single().terminations).isEqualTo(1)
        assertThat(f.keep.runs).isEmpty()
        assertThat(f.manager.sessions.value).isEmpty()
    }

    @Test fun `保活建立失败不启动PTY`() = runTest {
        val f = fixture()
        f.keep.failAcquire = true
        assertThat(runCatching { f.manager.open(LinuxTerminalLocation.Directory()) }.isFailure).isTrue()
        runCurrent()
        assertThat(f.ptys.single().starts).isEqualTo(0)
        assertThat(f.keep.runs).isEmpty()
        assertThat(f.manager.sessions.value).isEmpty()
    }

    @Test fun `并发打开保持列表顺序和唯一序号`() = runTest {
        val f = fixture()
        val opens = List(12) { async { f.manager.open(LinuxTerminalLocation.Directory()) } }
        opens.forEach { it.await() }
        assertThat(f.manager.sessions.value.map { it.ordinal }).containsExactlyElementsIn(1..12).inOrder()
        assertThat(f.manager.sessions.value.map { it.id }.distinct()).hasSize(12)
        assertThat(f.keep.runs).hasSize(12)
    }

    @Test fun `应用作用域结束后不允许启动新会话`() = runTest {
        val f = fixture()
        backgroundScope.cancel()
        assertThat(runCatching { f.manager.open(LinuxTerminalLocation.Directory()) }.isFailure).isTrue()
        assertThat(f.ptys).isEmpty()
    }

    @Test fun `终止失败保留可重试的会话与保活`() = runTest {
        val f = fixture()
        val id = f.manager.open(LinuxTerminalLocation.Directory())
        f.ptys.single().failTerminate = true
        f.manager.close(id)
        runCurrent()
        assertThat(f.manager.sessions.value.single().running).isTrue()
        assertThat(f.keep.runs.keys).containsExactly(id)
        f.ptys.single().failTerminate = false
        f.manager.close(id)
        runCurrent()
        assertThat(f.ptys.single().terminations).isEqualTo(2)
        assertThat(f.manager.sessions.value).isEmpty()
        assertThat(f.keep.runs).isEmpty()
    }

    private fun TestScope.fixture() = Fixture(this)

    private class Fixture(scope: TestScope) {
        val runtime = Runtime()
        val keep = KeepAlive()
        val ptys = mutableListOf<Pty>()
        val launches = mutableListOf<LinuxProcessLaunch>()
        val resolved = mutableListOf<String>()
        val reconciled = mutableListOf<String>()
        var workspace: File? = File("/workspace-host")
        var resolveFailure: Exception? = null
        var failStart = false
        var wakeBox: File? = null
        val wakeSessions = mutableListOf<String>()
        val manager = LinuxTerminalSessionsImpl(runtime, scope.backgroundScope, LinuxHostServices(keep,
            sessionHome = { File("/private-$it") }, reconcileSession = { reconciled += it }) { id ->
            resolved += id
            resolveFailure?.let { throw it }
            workspace
        }, LinuxPtyFactory { launch, client ->
            launches += launch
            Pty(client, failStart).also { ptys += it }
        }, StandardTestDispatcher(scope.testScheduler), StandardTestDispatcher(scope.testScheduler), { 123L },
            wakeInbox = { wakeSessions += it; wakeBox })
    }

    private class Runtime : LinuxRuntime by UnavailableLinuxRuntime(
        LinuxUnavailableReason.DISTRIBUTION, LinuxPaths(File("/rootfs"), File("/rootfs/root")),
    ) {
        override var paths = LinuxPaths(File("/rootfs"), File("/rootfs/root"))
        override val state = MutableStateFlow<LinuxRuntimeState>(LinuxRuntimeState.Ready(
            LinuxEnvironmentInfo("Alpine", "test", "aarch64", "test", 0, null),
        ))
        val specs = mutableListOf<LinuxLaunchSpec>()
        var prepareGate: CompletableDeferred<Unit>? = null
        val launch = LinuxProcessLaunch("/lib/proot.so", listOf("--kill-on-exit", "-r", "/rootfs", "/bin/sh", "-l"),
            mapOf("TERM" to "xterm-256color"), File("/host"))
        override suspend fun prepareLaunch(spec: LinuxLaunchSpec): LinuxProcessLaunch {
            specs += spec
            prepareGate?.await()
            return launch
        }
    }

    private class Pty(val client: TerminalSessionClient, val failStart: Boolean) : LinuxPty {
        override val session: TerminalSession? = null
        override var title: String? = null
            private set
        override var exitCode: Int? = null
        var starts = 0
        var terminations = 0
        var terminationGate: CompletableDeferred<Unit>? = null
        var failTerminate = false
        var failWrite = false
        var finishOnCommand = false
        val writes = mutableListOf<String>()
        override fun start() { starts++; check(!failStart) { "PTY unavailable" } }
        override suspend fun write(text: String) {
            check(!failWrite) { "PTY write failed" }
            writes += text
            if (finishOnCommand && writes.size > 1) finish(0)
        }
        override suspend fun terminate() {
            terminations++
            check(!failTerminate) { "terminate failed" }
            terminationGate?.await()
            finish(-9)
        }
        fun finish(code: Int) { exitCode = code; client.onSessionFinished(null) }
        fun emitTitle(value: String) { title = value; client.onTitleChanged(null) }
    }

    private class KeepAlive : ExecKeepAlive {
        val runs = linkedMapOf<String, ExecRunInfo>()
        val stops = linkedMapOf<String, () -> Unit>()
        var acquireGate: CompletableDeferred<Unit>? = null
        var failAcquire = false
        override suspend fun <T> hold(run: ExecRunInfo, stop: () -> Unit, block: suspend () -> T): ExecHeld<T> {
            check(!failAcquire) { "keep alive unavailable" }
            runs[run.id] = run
            stops[run.id] = stop
            return try { acquireGate?.await(); ExecHeld(block(), ExecForegroundState.STARTED) } finally { runs.remove(run.id) }
        }
    }

    private class Client : TerminalSessionClient by LinuxTerminalSilentClient {
        var titles = 0
        override fun onTitleChanged(changedSession: TerminalSession?) { titles++ }
    }
}
