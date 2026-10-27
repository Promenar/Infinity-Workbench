package com.promenar.nexara.data.branch

import com.promenar.nexara.data.exec.BuiltinCommandRunner
import com.promenar.nexara.data.exec.BuiltinExecEnvironment
import com.promenar.nexara.data.exec.BuiltinExecService
import com.promenar.nexara.data.exec.PackagedPython
import com.promenar.nexara.data.jobs.BackgroundJobLocation
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.repository.WorkspaceCommandHost
import com.promenar.nexara.data.repository.WorkspaceCommandOutcome
import com.promenar.nexara.data.workspace.SessionWorkspace
import com.promenar.nexara.domain.jobs.BackgroundJobRunner
import com.promenar.nexara.domain.linux.LinuxEnvironmentInfo
import com.promenar.nexara.domain.linux.LinuxLaunchSpec
import com.promenar.nexara.domain.linux.LinuxPaths
import com.promenar.nexara.domain.linux.LinuxProcessLaunch
import com.promenar.nexara.domain.linux.LinuxRuntime
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxUnavailableException
import com.promenar.nexara.domain.model.SessionRunMode
import com.promenar.nexara.startup.StartupWriteBarrier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

class BranchLinuxCommandServiceTest {
    private val temporary = Files.createTempDirectory("branch-linux-command").toFile().canonicalFile
    private val base = File(temporary, "parent").apply { mkdirs() }
    private val child = File(base, "branches/child").apply { mkdirs() }
    private val childHome = File(temporary, "private-child").apply { mkdirs() }
    private val specifications = mutableListOf<LinuxLaunchSpec>()
    private val resolvedSessions = mutableListOf<String>()
    private var abortLaunch = false
    private val state = MutableStateFlow<LinuxRuntimeState>(LinuxRuntimeState.Ready(
        LinuxEnvironmentInfo("test", "test", "test", "test", 1, null)))
    private val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, args ->
        when (method.name) {
            "getState" -> state
            "getPaths" -> LinuxPaths(File(temporary, "rootfs"), File(temporary, "rootfs/root"))
            "prepareLaunch" -> {
                val spec = args!![0] as LinuxLaunchSpec
                specifications += spec
                if (abortLaunch) throw LinuxUnavailableException(LinuxRuntimeState.NotInstalled(0))
                LinuxProcessLaunch("/not-executed", spec.argv, spec.environment, base)
            }
            else -> error("未预期的 Linux 运行时调用：${method.name}")
        }
    } as LinuxRuntime

    @Before fun setUp() { StartupWriteBarrier.resetForTesting() }
    @After fun tearDown() { StartupWriteBarrier.resetForTesting(); temporary.deleteRecursively() }

    private fun service(binding: suspend (String) -> BranchLinuxCommandWorkspace? = { sessionId ->
        resolvedSessions += sessionId
        BranchLinuxCommandWorkspace(base, "/workspace/branches/child")
    }): BuiltinExecService = BuiltinExecService(
        workspace = object : WorkspaceCommandHost {
            override suspend fun <T> runCommandInWorkspace(workspaceRootUuid: String, sessionId: String?, block: suspend (File) -> T): WorkspaceCommandOutcome<T> =
                error("分支目录工作区不应锁定或切换到其它私有根")
        },
        runner = BuiltinCommandRunner(listOf("/not-executed"), "/not-executed", { _, _ -> }, { File(temporary, "scratch") },
            terminalFactory = { _, _, _ -> error("规格测试不得启动真实进程") }),
        environment = BuiltinExecEnvironment(PackagedPython(File(temporary, "absent")), File(temporary, "home"),
            File(temporary, "tmp"), { null }, inherited = emptyMap(), tailnetEnvironment = { emptyMap() }),
        linuxRuntime = runtime,
        sessionWorkspace = { SessionWorkspace.Directory(WorkspaceEntity("branch", "project", "分支", "branch", 1), child) },
        sessionMode = { SessionRunMode.LINUX },
        sessionHome = { sessionId -> assertEquals("branch", sessionId); childHome },
        linuxLaunchBinding = binding,
    )

    @Test fun `前台入口保留父挂载分支cwd和子会话私有home`() = runTest {
        // 规格交给运行时后立即中止，因此不会启动 shell、访问网络或执行 Git。
        abortLaunch = true
        service().runShell("child-root", "branch", "git status", null, 1)
        val spec = specifications.single()
        assertEquals(base, spec.workspace)
        assertEquals("/workspace/branches/child", spec.workingDirectory)
        assertEquals("/workspace/branches/child", spec.environment["NEXARA_WORKSPACE"])
        assertEquals(childHome, spec.sessionHome)
        assertEquals(listOf("branch"), resolvedSessions)
    }

    @Test fun `后台入口从location恢复session绑定且只准备命令不启动进程`() = runTest {
        val prepared = service().prepareBackgroundCommand(BackgroundJobRunner.LINUX, "git status", null,
            BackgroundJobLocation(child, child, false, false, sessionId = "branch"), 1)
        try {
            val spec = specifications.single()
            assertEquals(base, spec.workspace)
            assertEquals("/workspace/branches/child", spec.workingDirectory)
            assertEquals(childHome, spec.sessionHome)
            assertEquals("/workspace/branches/child", prepared.request.environment["NEXARA_WORKSPACE"])
            assertEquals(listOf("branch"), resolvedSessions)
            assertEquals("/not-executed", prepared.request.argv.first())
        } finally { prepared.close() }
    }

    @Test fun `普通后台Linux会话保留原workspace绑定和默认cwd`() = runTest {
        val prepared = service { null }.prepareBackgroundCommand(BackgroundJobRunner.LINUX, "pwd", null,
            BackgroundJobLocation(child, child, false, false, sessionId = "branch"), 1, "branch")
        try {
            assertEquals(child, specifications.single().workspace)
            assertNull(specifications.single().workingDirectory)
            assertEquals("/workspace", prepared.request.environment["NEXARA_WORKSPACE"])
            assertEquals(childHome, specifications.single().sessionHome)
        } finally { prepared.close() }
    }

    @Test fun `分支绑定解析失败时前后台都拒绝启动不回退`() = runTest {
        val service = service { error("父工作区不可用") }
        assertEquals("EXEC_WORKSPACE_UNAVAILABLE", service.runShell("child-root", "branch", "pwd", null, 1).errorCode)
        assertTrue(runCatching { service.prepareBackgroundCommand(BackgroundJobRunner.LINUX, "pwd", null,
            BackgroundJobLocation(child, child, false, false, sessionId = "branch"), 1) }.exceptionOrNull() is IllegalStateException)
        assertTrue(specifications.isEmpty())
    }
}
