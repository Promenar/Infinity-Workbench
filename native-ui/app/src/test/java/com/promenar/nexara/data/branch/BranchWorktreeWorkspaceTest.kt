package com.promenar.nexara.data.branch

import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.dao.WorkspaceDao
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.storage.RealPathExternalFolderBackend
import com.promenar.nexara.data.workspace.SessionWorkspace
import com.promenar.nexara.data.workspace.SessionWorkspaceResolver
import com.promenar.nexara.data.workspace.SharedWorkspaceAccess
import com.promenar.nexara.domain.linux.LinuxEnvironmentInfo
import com.promenar.nexara.domain.linux.LinuxPaths
import com.promenar.nexara.domain.linux.LinuxRuntime
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.storage.ExternalFolderBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

class BranchWorktreeWorkspaceTest {
    private val temporary = Files.createTempDirectory("branch-workspace").toFile().canonicalFile
    private val rootfs = File(temporary, "rootfs").apply { mkdirs() }
    private val directory = File(rootfs, "root/branch").apply { mkdirs() }
    private val ready = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("test", "test", "test", "test", 1, null))
    private val state = MutableStateFlow<LinuxRuntimeState>(ready)
    private val session = SessionEntity("branch", "agent", workspaceId = "must-not-fallback", worktreeDirectory = "/root/branch",
        worktreeBranch = "feature", worktreeBase = "a".repeat(40), createdAt = 1, updatedAt = 1)
    private val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, _ ->
        when (method.name) { "getState" -> state; "getPaths" -> LinuxPaths(rootfs, File(rootfs, "root")); else -> error("未预期的运行时调用") }
    } as LinuxRuntime

    @After fun tearDown() { temporary.deleteRecursively() }

    private fun workspaceResolver(binding: BranchWorktreeWorkspaceResolver?, shared: WorkspaceEntity? = null,
        sharedRoot: File? = null, candidate: SessionEntity = session): SessionWorkspaceResolver {
        val sessionDao = Proxy.newProxyInstance(SessionDao::class.java.classLoader, arrayOf(SessionDao::class.java)) { _, method, _ ->
            if (method.name == "getById") candidate else error("未预期的会话DAO调用")
        } as SessionDao
        val workspaceDao = Proxy.newProxyInstance(WorkspaceDao::class.java.classLoader, arrayOf(WorkspaceDao::class.java)) { _, method, _ ->
            if (shared != null && method.name == "getById") shared else error("绑定worktree时不能读取回退工作区")
        } as WorkspaceDao
        return SessionWorkspaceResolver(sessionDao, workspaceDao, temporary, object : SharedWorkspaceAccess {
            override fun root(location: String): File = error("不能回退")
            override fun backend(root: File): ExternalFolderBackend = RealPathExternalFolderBackend(root)
        }, branchWorktreeResolver = binding, resolveSharedPrivateRoot = { uuid ->
            assertEquals(shared?.location, uuid)
            sharedRoot
        })
    }

    @Test fun `Linux路径映射到确切宿主根且优先于workspaceId`() = runTest {
        val result = workspaceResolver(BranchWorktreeWorkspaceResolver(runtime)).resolve(session.id) as SessionWorkspace.Directory
        assertEquals(directory, result.root)
        assertEquals("/root/branch", result.workspace.location)
        assertEquals("branch-worktree:branch", result.workspace.id)
        assertEquals(BranchWorktreeWorkspaceResolver.KIND, result.kind)
        assertEquals("project", SessionWorkspaceResolver.kindOf(result))
    }

    @Test fun `worktree创建失败或尚未绑定时不回退共用工作区`() = runTest {
        val pending = session.copy(worktreeDirectory = null, worktreeBranch = null,
            branchPlanJson = BranchPlanProtocol.encode(com.promenar.nexara.domain.branch.BranchPlan("任务", "验收", listOf("执行"),
                workMode = com.promenar.nexara.domain.branch.BranchWorkMode.WORKTREE,
                worktree = com.promenar.nexara.domain.branch.BranchWorktree("feat/test", "main", "/root/branch", "隔离"), approvalMode = "workspace")))
        assertTrue(workspaceResolver(null, candidate = pending).resolve(pending.id) is SessionWorkspace.Unavailable)
    }
    @Test fun `Linux不可用或解析器缺失时明确失败且不回退`() = runTest {
        assertTrue(workspaceResolver(null).resolve(session.id) is SessionWorkspace.Unavailable)
        state.value = LinuxRuntimeState.NotInstalled(0)
        assertTrue(workspaceResolver(BranchWorktreeWorkspaceResolver(runtime)).resolve(session.id) is SessionWorkspace.Unavailable)
    }

    @Test fun `目录删除或中间符号链接时拒绝`() = runTest {
        directory.delete()
        assertTrue(workspaceResolver(BranchWorktreeWorkspaceResolver(runtime)).resolve(session.id) is SessionWorkspace.Unavailable)
        File(rootfs, "root").delete()
        val outside = File(temporary, "outside").apply { mkdirs() }
        File(outside, "branch").mkdirs()
        Files.createSymbolicLink(File(rootfs, "root").toPath(), outside.toPath())
        assertTrue(workspaceResolver(BranchWorktreeWorkspaceResolver(runtime)).resolve(session.id) is SessionWorkspace.Unavailable)
    }

    @Test fun `挂载路径只有明确映射才采用`() = runTest {
        val mounted = session.copy(worktreeDirectory = "/workspace/branch")
        val resolver = BranchWorktreeWorkspaceResolver(runtime) { candidate, guest ->
            assertEquals(session.id, candidate.id)
            assertEquals("/workspace/branch", guest)
            directory
        }
        assertEquals(directory, resolver.resolve(mounted).root)
        var failed = false
        try { BranchWorktreeWorkspaceResolver(runtime).resolve(mounted) } catch (_: Exception) { failed = true }
        assertTrue(failed)
    }

    @Test fun `共享父私有工作区只通过逻辑根UUID解析不复用分支私有根`() = runTest {
        val shared = WorkspaceEntity("shared", "branch_shared", "父工作区", java.util.UUID.randomUUID().toString(), 1)
        val resolver = workspaceResolver(null, shared, directory)
        val result = resolver.resolveWorkspace(shared.id) as SessionWorkspace.Directory
        assertEquals(directory, result.root)
        assertEquals(shared.location, result.workspace.location)
        assertEquals("project", SessionWorkspaceResolver.kindOf(result))
    }

    @Test fun `共享根缺失或是符号链接时失败关闭`() = runTest {
        val shared = WorkspaceEntity("shared", "branch_shared", "父工作区", java.util.UUID.randomUUID().toString(), 1)
        assertTrue(workspaceResolver(null, shared).resolveWorkspace(shared.id) is SessionWorkspace.Unavailable)
        val link = File(temporary, "link")
        Files.createSymbolicLink(link.toPath(), directory.toPath())
        assertTrue(workspaceResolver(null, shared, link).resolveWorkspace(shared.id) is SessionWorkspace.Unavailable)
    }
}
