package com.promenar.nexara.data.exec

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.jobs.*
import com.promenar.nexara.data.repository.*
import com.promenar.nexara.data.storage.ExternalMountPaths
import com.promenar.nexara.domain.jobs.*
import com.promenar.nexara.domain.linux.*
import com.promenar.nexara.domain.model.SessionRunMode
import com.promenar.nexara.domain.model.ExecutionMode
import com.promenar.nexara.domain.storage.ExternalMount
import com.promenar.nexara.domain.tool.*
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.ui.chat.manager.SystemPromptSections
import com.promenar.nexara.ui.chat.manager.skills.ShellExecSkill
import com.promenar.nexara.ui.chat.manager.skills.backgroundStartJson
import com.promenar.nexara.ui.chat.manager.skills.backgroundJobTools
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.workspace.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class LinuxExecIntegrationTest {
    private lateinit var root: File
    private lateinit var workspace: File
    private lateinit var runtime: FakeLinuxRuntime
    private lateinit var runner: BuiltinCommandRunner
    private val scopes = mutableListOf<CoroutineScope>()
    private val keepKinds = mutableListOf<ExecRunKind>()
    private var homeResolutions = 0
    private var reconciles = 0
    private var mode: SessionRunMode? = SessionRunMode.LINUX

    @Before fun setUp() {
        root = Files.createTempDirectory("linux-exec").toFile()
        workspace = File(root, "workspace").apply { mkdirs() }
        runtime = FakeLinuxRuntime(root)
        runner = BuiltinCommandRunner(
            groupLauncher = listOf("/usr/bin/perl", "-e", "setpgrp(0, 0); exec @ARGV or exit 127"),
            shell = "/bin/sh",
            signaller = { group, signal -> ProcessBuilder("/bin/kill", "-$signal", "-$group").start().waitFor() },
            scratchDirectory = { File(root, "scratch") }, killGraceMillis = 50, pollMillis = 20,
        )
    }

    @After fun tearDown() = runBlocking {
        com.promenar.nexara.startup.StartupWriteBarrier.resetForTesting()
        scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() }
        root.deleteRecursively()
        Unit
    }

    private val keepAlive = object : ExecKeepAlive {
        override suspend fun <T> hold(run: ExecRunInfo, stop: () -> Unit, block: suspend () -> T): ExecHeld<T> {
            synchronized(keepKinds) { keepKinds += run.kind }
            return ExecHeld(block(), ExecForegroundState.STARTED)
        }
    }

    private fun service(provenance: LinuxPackageProvenance? = null, mounts: List<ExternalMount> = emptyList()) = BuiltinExecService(
        workspace = object : WorkspaceCommandHost {
            override suspend fun <T> runCommandInWorkspace(workspaceRootUuid: String, sessionId: String?, block: suspend (File) -> T): WorkspaceCommandOutcome<T> {
                val result = block(workspace)
                reconciles++
                return WorkspaceCommandOutcome(result, WorkspaceChangeReport(added = workspace.listFiles().orEmpty().filter { it.isFile }.map { "/${it.name}" }))
            }
        },
        runner = runner,
        environment = BuiltinExecEnvironment(PackagedPython(File(root, "absent")), File(root, "home"), File(root, "tmp"), { null }, inherited = mapOf("PATH" to "/usr/bin:/bin")),
        shell = "/bin/sh", keepAlive = keepAlive, linuxRuntime = runtime, linuxProvenance = provenance,
        visibleMounts = { assertThat(it).isEqualTo("s"); mounts }, sessionTitle = { "会话标题" }, sessionMode = { mode }, sessionHome = { homeResolutions++; workspace },
    )

    private suspend fun shell(service: BuiltinExecService, command: String, timeout: Int = 3, live: ToolLiveOutputSink = ToolLiveOutputSink.None) =
        service.runShell("root", "s", command, null, timeout, live)


    @Test fun `隔离工作目录的前后台Linux命令均在生成启动规格前拒绝`() = runBlocking {
        val manager = com.promenar.nexara.startup.StartupIssueManager()
        com.promenar.nexara.startup.StartupWriteBarrier.install(manager)
        manager.bindRoot("root", workspace.canonicalPath, "s")
        manager.recover(com.promenar.nexara.startup.StartupStep.WORKSPACE_JOURNAL,
            com.promenar.nexara.startup.StartupIssueScope(com.promenar.nexara.startup.StartupScopeKind.WORKSPACE_ROOT, "root", "工作区")) { error("冲突") }
        val service = service()
        assertThat(runCatching { shell(service, "touch blocked") }.exceptionOrNull())
            .isInstanceOf(com.promenar.nexara.startup.StartupIsolationException::class.java)
        assertThat(runCatching { service.prepareBackgroundCommand(BackgroundJobRunner.LINUX, "touch blocked", null,
            BackgroundJobLocation(workspace, workspace, false, false), 1, "s") }.exceptionOrNull())
            .isInstanceOf(com.promenar.nexara.startup.StartupIsolationException::class.java)
        assertThat(runtime.specs).isEmpty()
        assertThat(File(workspace, "blocked").exists()).isFalse()
    }

    @Test fun `宿主身份包装在 guest PATH 下仍可落盘并保持 guest 环境`() {
        val stat = File(root, "stat").apply { writeText("1 (shell) " + (listOf("S") + List(18) { "0" } + "123").joinToString(" ")) }
        val pid = File(root, "pid")
        val request = BuiltinCommandRequest(listOf("/bin/sh", "-c", "printf '%s' \"\$PATH\""), workspace, mapOf("PATH" to "/guest/bin"), timeoutSeconds = 3)
        // 固定 stat 验证身份落盘；同名函数模拟宿主 shell 没有 printf 内建且 guest PATH 不提供宿主工具。
        val command = runner.launchCommand(request, pid, true).map {
            if (it.contains("__nexara_stat=")) "printf() { return 127; }; " + it.replace("/proc/\$\$/stat", "'${stat.path}'") else it
        }
        assertThat(command.dropLast(request.argv.size).takeLast(3).all { File(it).isAbsolute && File(it).canExecute() }).isTrue()
        fun execute(argv: List<String>): Pair<Int, String> {
            val process = ProcessBuilder(argv).redirectErrorStream(true).apply { environment().clear(); environment().putAll(request.environment) }.start()
            val output = process.inputStream.bufferedReader().readText()
            return process.waitFor() to output
        }
        val broken = command.map { if(it.contains("__nexara_stat=")) it.replace("\"\$4\"", "cat").replace("\"\$5\"", "mv") else it }
        assertThat(execute(broken).first).isEqualTo(125)
        val missingPrintf = command.map {
            if(it.contains("__nexara_stat=")) it.replace("__nexara_printf=\$6", "__nexara_printf=printf").replace("\"\$6\"", "printf") else it
        }
        assertThat(execute(missingPrintf).first).isEqualTo(125)
        val result = execute(command)
        assertThat(result.first).isEqualTo(0)
        assertThat(result.second).isEqualTo("/guest/bin")
        assertThat(File(pid.path + ".identity").readText().lines().last()).isEqualTo("123")
    }

    @Test fun `Linux 启动规格绑定工作区真实挂载和技能且不泄漏系统 Python 环境`() = runBlocking<Unit> {
        val mount = File(root, "mounted").apply { mkdirs() }
        val mounts = listOf(
            ExternalMount("p", "资料", "real_path", mount.path, "s", 0),
            ExternalMount("saf", "saf", "saf_tree", "content://tree", "s", 0),
            ExternalMount("gone", "gone", "real_path", mount.path, "s", 0, "撤权"),
            ExternalMount(ExternalMountPaths.SKILLS_MOUNT_ID, "skills", ExternalMountPaths.SKILLS_SOURCE, "", "s", 0),
        )
        val result = shell(service(mounts = mounts), "printf '%s' \"\$NEXARA_WORKSPACE\"; echo ok > out.txt")
        val spec = runtime.specs.single()
        assertThat(spec.argv).containsExactly("/bin/sh", "-c", "printf '%s' \"\$NEXARA_WORKSPACE\"; echo ok > out.txt").inOrder()
        assertThat(spec.workspace).isEqualTo(workspace)
        assertThat(spec.sessionHome).isEqualTo(workspace)
        assertThat(homeResolutions).isEqualTo(0)
        assertThat(spec.mounts.map { it.guestPath }).containsExactly("/mnt/资料", "/mnt/skills")
        assertThat(spec.mounts.first().host).isEqualTo(mount)
        assertThat(spec.environment).containsEntry("NEXARA_SKILLS", "/mnt/skills")
        assertThat(spec.environment).doesNotContainKey("NEXARA_PYTHON")
        assertThat(result.stdout).isEqualTo("/workspace")
        assertThat(result.toJson()["runtime"]?.jsonPrimitive?.content).isEqualTo("linux")
        assertThat(result.toJson()["workspace_changes"]?.jsonObject?.get("added")?.jsonArray).contains(JsonPrimitive("/out.txt"))
        assertThat(keepKinds).containsExactly(ExecRunKind.LINUX)
        assertThat(reconciles).isEqualTo(1)
    }

    @Test fun `Linux 会话启动前不可用时明确失败且不会执行系统 shell`() = runBlocking<Unit> {
        runtime.unavailableOnPrepare = true
        val result = shell(service(), "echo forbidden > fallback.txt")
        assertThat(result.errorCode).isEqualTo("EXEC_LINUX_UNAVAILABLE")
        assertThat(result.toJson()["runtime"]?.jsonPrimitive?.content).isEqualTo("linux")
        assertThat(result.toJson()).doesNotContainKey("runtime_fallback")
        assertThat(File(workspace, "fallback.txt").exists()).isFalse()
    }

    @Test fun `Linux 会话在每一种不可用状态均拒绝执行且不回退`() = runBlocking<Unit> {
        states().filterNot { it is LinuxRuntimeState.Ready }.forEach { state ->
            runtime.state.value = state
            val result = shell(service(), "touch forbidden")
            assertThat(result.errorCode).isEqualTo("EXEC_LINUX_UNAVAILABLE")
            assertThat(result.toJson()["runtime"]?.jsonPrimitive?.content).isEqualTo("linux")
        }
        assertThat(File(workspace, "forbidden").exists()).isFalse()
        assertThat(runtime.specs).isEmpty()
    }

    @Test fun `内置会话不随 Linux 状态或默认设置切换`() = runBlocking<Unit> {
        mode = SessionRunMode.BUILTIN
        for (state in states()) {
            runtime.state.value = state
            for (enabled in listOf(false, true)) {
                runtime.agentEnabled.value = enabled
                assertThat(shell(service(), "printf builtin").stdout).isEqualTo("builtin")
            }
        }
        assertThat(runtime.specs).isEmpty()
    }

    @Test fun `未确定模式和 Linux 会话的内置 Python 均不可调用`() = runBlocking<Unit> {
        mode = null
        assertThat(shell(service(), "touch forbidden").errorCode).isEqualTo("EXEC_RUN_MODE_REQUIRED")
        assertThat(service().runPython("root", "s", "print(1)", null, 1).errorCode).isEqualTo("EXEC_RUN_MODE_REQUIRED")
        mode = SessionRunMode.LINUX
        assertThat(service().runPython("root", "s", "print(1)", null, 1).errorCode).isEqualTo("EXEC_MODE_TOOL_UNAVAILABLE")
        assertThat(File(workspace, "forbidden").exists()).isFalse()
    }

    @Test fun `Linux 会话不随新会话默认模式改用系统`() = runBlocking<Unit> {
        runtime.agentEnabled.value = false
        assertThat(shell(service(), "printf linux").toJson()["runtime"]?.jsonPrimitive?.content).isEqualTo("linux")
        assertThat(runtime.specs).hasSize(1)
    }

    @Test fun `审批后模式改变不得在另一种环境执行命令`() = runBlocking<Unit> {
        for ((current, expected) in listOf(SessionRunMode.LINUX to SessionRunMode.BUILTIN, SessionRunMode.BUILTIN to SessionRunMode.LINUX)) {
            mode = current
            val result = service().runShell("root", "s", "touch forbidden", null, 1, expectedMode = expected)
            assertThat(result.errorCode).isEqualTo("EXEC_MODE_CHANGED")
        }
        assertThat(File(workspace, "forbidden").exists()).isFalse()
        assertThat(runtime.specs).isEmpty()
    }

    @Test fun `后台任务在审批后切换模式时拒绝预留或启动`() = runBlocking<Unit> {
        mode = SessionRunMode.LINUX
        val jobs = manager(service())
        val failure = runCatching { jobs.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "touch forbidden", null, 1, BackgroundJobExitAction.NONE, expectedMode = SessionRunMode.BUILTIN) }.exceptionOrNull()
        assertThat((failure as? JobAccessException)?.code).isEqualTo("EXEC_MODE_CHANGED")
        assertThat(jobs.list("s")).isEmpty()
        assertThat(File(workspace, "forbidden").exists()).isFalse()
    }

    @Test fun `Linux 输出保留 guest 工作区并虚拟化宿主路径及实时输出`() = runBlocking<Unit> {
        val live = StringBuilder()
        val sink = object : ToolLiveOutputSink by ToolLiveOutputSink.None {
            override fun emit(stream: ToolOutputStream, text: String) { synchronized(live) { live.append(text) } }
        }
        val result = shell(service(), "printf '/workspace/out.txt\\n'; pwd", live = sink)
        assertThat(result.stdout).isEqualTo("/workspace/out.txt\n\$NEXARA_WORKSPACE\n")
        assertThat(live.toString()).isEqualTo(result.stdout)
        assertThat(result.stdout).doesNotContain(workspace.path)
    }

    @Test fun `软件包来源在命令前后记录失败退出仍归属当前会话`() = runBlocking<Unit> {
        val provenance = RecordingProvenance(workspace)
        val result = shell(service(provenance), "touch installed; exit 7")
        assertThat(result.exitCode).isEqualTo(7)
        assertThat(provenance.events).containsExactly("before", "after", "attribute").inOrder()
        assertThat(provenance.origin).isEqualTo(LinuxPackageOrigin.Agent("s", "会话标题"))
    }

    @Test fun `来源快照和归属失败不改变工具结果且系统路径不调用来源记录`() = runBlocking<Unit> {
        for (stage in listOf("before", "after", "attribute")) {
            File(workspace, "installed").delete()
            val provenance = RecordingProvenance(workspace, stage)
            assertThat(shell(service(provenance), "touch installed; printf ok").stdout).isEqualTo("ok")
            assertThat(provenance.events).contains("after")
        }
        val provenance = RecordingProvenance(workspace)
        mode = SessionRunMode.BUILTIN
        shell(service(provenance), "true")
        assertThat(provenance.events).isEmpty()
    }

    @Test fun `没有新增包或起始快照不可用时只采集两次快照`() = runBlocking<Unit> {
        val unchanged = RecordingProvenance(workspace)
        assertThat(shell(service(unchanged), "true").exitCode).isEqualTo(0)
        assertThat(unchanged.events).containsExactly("before", "after").inOrder()
        var snapshots = 0
        var attributions = 0
        val missing = object : LinuxPackageProvenance {
            override suspend fun worldSnapshot(): Set<String>? = if (snapshots++ == 0) null else setOf("git")
            override suspend fun attributeNewPackages(before: Set<String>, origin: LinuxPackageOrigin) { attributions++ }
        }
        assertThat(shell(service(missing), "true").exitCode).isEqualTo(0)
        assertThat(snapshots).isEqualTo(2)
        assertThat(attributions).isEqualTo(0)
    }

    @Test fun `超时和用户停止仍对账并执行结束快照`() = runBlocking<Unit> {
        val provenance = RecordingProvenance(workspace)
        val result = shell(service(provenance), "touch installed; sleep 30", timeout = 1)
        assertThat(result.errorCode).isEqualTo("EXEC_TIMEOUT")
        assertThat(provenance.events).containsExactly("before", "after", "attribute").inOrder()
        assertThat(reconciles).isEqualTo(1)
        val stopped = shell(service(), "sleep 30", live = object : ToolLiveOutputSink by ToolLiveOutputSink.None {
            override fun emit(stream: ToolOutputStream, text: String) = Unit
            override fun onStop(stop: () -> Unit) = stop()
        })
        assertThat(stopped.errorCode).isEqualTo("EXEC_STOPPED")
    }

    @Test fun `取消命令也完成软件包归属的收尾`() = runBlocking<Unit> {
        val provenance = RecordingProvenance(workspace)
        val task = launch(Dispatchers.IO) { shell(service(provenance), "touch installed; sleep 30") }
        withTimeout(5000) { while (!File(workspace, "installed").exists()) delay(10) }
        task.cancelAndJoin()
        assertThat(provenance.events).containsExactly("before", "after", "attribute").inOrder()
    }

    @Test fun `分类与审批目标区分 Linux 但所有工作区命令仍逐条批准`() {
        val tool = ProtocolTool(function = ProtocolToolFunction("shell_exec", "", "{}"), risk = ToolRisk.PROCESS, sourceId = "builtin")
        for (linux in listOf(true, false)) {
            assertThat(CommandToolClassification.backendFor("shell_exec", linux)).isEqualTo(if(linux) CommandToolBackend.LINUX else CommandToolBackend.BUILTIN_SHELL)
            assertThat(CommandToolClassification.backendFor("python_exec", linux)).isEqualTo(CommandToolBackend.BUILTIN_PYTHON)
            assertThat(ToolExecutionTargets.extract(tool, """{"command":"echo ok"}""", usesLinux = linux)).isEqualTo("""["process","${if(linux) "linux" else "builtin"}","echo ok"]""")
            assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.WORKSPACE, "shell_exec", ToolRisk.PROCESS, usesLinux = linux)).isTrue()
            assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.FULL_ACCESS, "shell_exec", ToolRisk.PROCESS, usesLinux = linux)).isFalse()
        }
    }

    @Test fun `提示词按固定会话模式而不按实时状态选择后端`() {
        for (state in states()) {
            val linux = SystemPromptSections.toolGuidance(setOf("shell_exec"), generic = false, linuxState = state, runMode = SessionRunMode.LINUX)
            listOf("apk add", "/workspace", "/home/session", "/mnt/<别名>", "/root", "元数据密集").forEach { assertThat(linux).contains(it) }
            assertThat(linux).doesNotContain("当前使用系统")
            val builtin = SystemPromptSections.toolGuidance(setOf("shell_exec", "python_exec"), generic = false, linuxState = state, runMode = SessionRunMode.BUILTIN)
            assertThat(builtin).contains("/system/bin/sh")
            assertThat(builtin).doesNotContain("Alpine Linux（musl）")
        }
    }

    @Test fun `可达范围列出 Linux 状态和跳转标识`() {
        val expected = listOf(ReachBackendStatus.UNAVAILABLE, ReachBackendStatus.NOT_INSTALLED, ReachBackendStatus.INSTALLING, ReachBackendStatus.BROKEN, ReachBackendStatus.READY, ReachBackendStatus.INSTALLING, ReachBackendStatus.BROKEN)
        // Play 渠道不列出 Linux 行；其它不可用原因（如 ABI）仍显示为不可用。
        assertThat(reachBackends(true, false, null, 0, states().first(), true).none { it.kind == ReachBackendKind.LINUX }).isTrue()
        val abi = LinuxRuntimeState.Unavailable(LinuxUnavailableReason.UNSUPPORTED_ABI)
        (listOf(abi) + states().drop(1)).zip(expected).forEach { (state, status) ->
            val backend = reachBackends(true, false, null, 0, state, true).single { it.kind == ReachBackendKind.LINUX }
            assertThat(backend.status).isEqualTo(status)
            assertThat(backend.destination).isEqualTo("linux_environment")
        }
        assertThat(linuxReachStatus(readyState(), false)).isEqualTo(ReachBackendStatus.DISABLED)
    }

    private fun manager(exec: BuiltinExecService, beforePrepare: suspend () -> Unit = {}): BackgroundJobManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return BackgroundJobManager(scope, BackgroundJobStore(File(root,"store")), File(root,"runs"), runner,
            prepareWorkspace = { BackgroundJobLocation(workspace, workspace, false, false) },
            prepareCommand = { kind, source, stdin, location, minutes -> beforePrepare(); exec.prepareBackgroundCommand(kind, source, stdin, location, minutes, location.sessionId) },
            keepAlive = keepAlive, linuxRuntime = runtime, sessionMode = { mode },
        )
    }

    @Test fun `后台 shell 创建时固定 Linux 执行器并归属软件包`() = runBlocking<Unit> {
        val provenance = RecordingProvenance(workspace)
        val manager = manager(service(provenance))
        val record = manager.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "touch installed; echo background", null, 1, BackgroundJobExitAction.NONE)
        assertThat(record.runnerKind).isEqualTo(BackgroundJobRunner.LINUX)
        assertThat(backgroundStartJson(record)["runtime"]?.jsonPrimitive?.content).isEqualTo("linux")
        val result = manager.waitFor("s", record.id, 5)
        assertThat(result.record.state).isEqualTo("EXITED")
        assertThat(result.tail.text).contains("background")
        assertThat(keepKinds).containsExactly(ExecRunKind.LINUX)
        assertThat(provenance.origin).isEqualTo(LinuxPackageOrigin.Agent("s", "会话标题"))
        val context = object : SkillExecutionContext {
            override val sessionId = "s"
            override val agentId = "agent"
            override val workspacePath: String? = null
            override val workspaceRootUuid = "root"
        }
        val status = backgroundJobTools(manager).first { it.id == "job_status" }.execute(buildJsonObject { put("job_id",record.id) }, context)
        assertThat(Json.parseToJsonElement(status.content).jsonObject["runtime"]?.jsonPrimitive?.content).isEqualTo("linux")
    }

    @Test fun `后台环境在创建后启动前卸载则失败且不回退系统`() = runBlocking<Unit> {
        val manager = manager(service()) { runtime.uninstall() }
        assertThat(runCatching { manager.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "touch forbidden", null, 1, BackgroundJobExitAction.NONE) }.exceptionOrNull()).isInstanceOf(LinuxUnavailableException::class.java)
        val record = manager.list("s").single()
        assertThat(record.runnerKind).isEqualTo(BackgroundJobRunner.LINUX)
        assertThat(record.state).isEqualTo("FAILED")
        assertThat(record.guidance).contains("Linux 环境")
        assertThat(File(workspace,"forbidden").exists()).isFalse()
    }

    @Test fun `后台执行期间卸载停止整个任务并提供可读失败原因`() = runBlocking<Unit> {
        val provenance = RecordingProvenance(workspace)
        val manager = manager(service(provenance))
        val record = manager.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "touch installed; sleep 30", null, 1, BackgroundJobExitAction.NONE)
        withTimeout(5000) { while(!File(workspace,"installed").exists()) delay(10) }
        runtime.uninstall()
        val result = manager.waitFor("s", record.id, 5)
        assertThat(result.record.state).isEqualTo("FAILED")
        assertThat(result.record.guidance).contains("Linux 环境当前不可用")
        assertThat(provenance.events).containsExactly("before", "after", "attribute").inOrder()
    }

    @Test fun `后台内置会话在 Linux 可用时仍使用系统执行器`() = runBlocking<Unit> {
        mode = SessionRunMode.BUILTIN
        val manager = manager(service())
        val record = manager.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "echo system", null, 1, BackgroundJobExitAction.NONE)
        assertThat(record.runnerKind).isEqualTo(BackgroundJobRunner.SHELL)
        assertThat(manager.waitFor("s", record.id, 5).record.state).isEqualTo("EXITED")
        assertThat(runtime.specs).isEmpty()
    }

    private class RecordingProvenance(private val workspace: File, private val failAt: String? = null) : LinuxPackageProvenance {
        val events = mutableListOf<String>()
        var origin: LinuxPackageOrigin? = null
        override suspend fun worldSnapshot(): Set<String> {
            val stage = if(events.isEmpty()) "before" else "after"
            events += stage
            if(failAt == stage) error("来源记录失败")
            return if(File(workspace,"installed").exists()) setOf("base", "git") else setOf("base")
        }
        override suspend fun attributeNewPackages(before: Set<String>, origin: LinuxPackageOrigin) {
            events += "attribute"
            assertThat(before).containsExactly("base")
            if(failAt == "attribute") error("归属失败")
            this.origin = origin
        }
    }
}

private fun readyState() = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "test", "host", "test", 0, null))
private fun states(): List<LinuxRuntimeState> {
    val progress = LinuxInstallProgress(emptyList(), null)
    return listOf(LinuxRuntimeState.Unavailable(LinuxUnavailableReason.DISTRIBUTION), LinuxRuntimeState.NotInstalled(0), LinuxRuntimeState.Installing(progress), LinuxRuntimeState.InstallInterrupted(progress, null), readyState(), LinuxRuntimeState.Maintaining(LinuxMaintenance.UNINSTALLING), LinuxRuntimeState.Broken(LinuxFailure(LinuxFailureCode.ROOTFS_DAMAGED)))
}

private class FakeLinuxRuntime(root: File) : LinuxRuntime {
    override val state = MutableStateFlow<LinuxRuntimeState>(readyState())
    override val agentEnabled = MutableStateFlow(true)
    override val paths = LinuxPaths(File(root,"rootfs"), File(root,"rootfs/root"))
    val specs = mutableListOf<LinuxLaunchSpec>()
    var unavailableOnPrepare = false
    override suspend fun setAgentEnabled(enabled: Boolean) { agentEnabled.value = enabled }
    override fun startInstall(options: LinuxInstallOptions) = Unit
    override fun cancelInstall() = Unit
    override suspend fun storageUsage() = LinuxStorageUsage(0,0,0,0)
    override suspend fun clearDownloadCache() = 0L
    override suspend fun runDiagnostics() = LinuxDiagnostics(true,0,0, emptyList())
    override suspend fun reset() = Unit
    override suspend fun uninstall() { state.value = LinuxRuntimeState.NotInstalled(0) }
    override suspend fun prepareLaunch(spec: LinuxLaunchSpec): LinuxProcessLaunch {
        if(unavailableOnPrepare || state.value !is LinuxRuntimeState.Ready) throw LinuxUnavailableException(LinuxRuntimeState.NotInstalled(0))
        specs += spec
        return LinuxProcessLaunch("/bin/sh", listOf("-c",spec.argv.last()), mapOf("PATH" to "/usr/bin:/bin") + spec.environment, checkNotNull(spec.workspace))
    }
}
