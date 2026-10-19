package com.promenar.nexara.data.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.dao.WorkspaceDao
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.domain.storage.ExternalFolderBackend
import com.promenar.nexara.domain.storage.ExternalFolderException
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

class SessionWorkspaceResolverTest {
    private val directory = Files.createTempDirectory("workspace-resolver").toFile()
    private val projects = File(directory, "projects").apply { mkdirs() }
    private val sessions = mutableMapOf<String, SessionEntity>()
    private val records = mutableMapOf<String, WorkspaceEntity>()
    private val sessionDao = mockk<SessionDao> { coEvery { getById(any()) } answers { sessions[firstArg()] } }
    private val workspaceDao = mockk<WorkspaceDao> { coEvery { getById(any()) } answers { records[firstArg()] } }
    private val sharedRoots = mutableListOf<String>()
    private val shared = object : SharedWorkspaceAccess {
        var failure: ExternalFolderException? = null
        override fun root(location: String): File {
            sharedRoots += location
            failure?.let { throw it }
            return File(location)
        }
        override fun backend(root: File): ExternalFolderBackend = mockk()
    }
    private val resolver = SessionWorkspaceResolver(sessionDao, workspaceDao, projects, shared)

    @After fun cleanup() { directory.deleteRecursively() }

    private fun bind(workspaceId: String?) {
        sessions["s"] = SessionEntity("s", "agent", workspaceId = workspaceId, createdAt = 1, updatedAt = 1)
    }

    @Test fun `未绑定或会话不存在时使用私有工作区`() = runBlocking<Unit> {
        bind(null)
        assertThat(resolver.resolve("s")).isEqualTo(SessionWorkspace.Private)
        assertThat(resolver.resolve("missing")).isEqualTo(SessionWorkspace.Private)
        assertThat(resolver.resolve(null)).isEqualTo(SessionWorkspace.Private)
        assertThat(SessionWorkspaceResolver.kindOf(SessionWorkspace.Private)).isEqualTo("private")
    }

    @Test fun `项目文件夹解析到私有项目根下的目录`() = runBlocking<Unit> {
        records["p"] = WorkspaceEntity("p", WorkspaceEntity.KIND_PROJECT, "项目", "repo", 1)
        bind("p")
        File(projects, "repo").mkdirs()
        val resolved = resolver.resolve("s") as SessionWorkspace.Directory
        assertThat(resolved.root).isEqualTo(File(projects.canonicalFile, "repo"))
        assertThat(SessionWorkspaceResolver.kindOf(resolved)).isEqualTo("project")
    }

    @Test fun `项目根经过系统符号链接时规范化后仍可用`() = runBlocking<Unit> {
        val linked = File(directory, "linked-files")
        Files.createSymbolicLink(linked.toPath(), directory.toPath())
        records["p"] = WorkspaceEntity("p", WorkspaceEntity.KIND_PROJECT, "项目", "repo", 1)
        bind("p")
        File(projects, "repo").mkdirs()
        val resolved = SessionWorkspaceResolver(sessionDao, workspaceDao, File(linked, "projects"), shared).resolve("s") as SessionWorkspace.Directory
        assertThat(resolved.root).isEqualTo(File(projects.canonicalFile, "repo"))
        assertThat(resolved.root.canonicalFile).isEqualTo(resolved.root)
    }

    @Test fun `项目目录缺失、是符号链接或登记已删除时不可用`() = runBlocking<Unit> {
        records["p"] = WorkspaceEntity("p", WorkspaceEntity.KIND_PROJECT, "项目", "repo", 1)
        bind("p")
        assertThat((resolver.resolve("s") as SessionWorkspace.Unavailable).reason).contains("不存在")
        val target = File(directory, "elsewhere").apply { mkdirs() }
        Files.createSymbolicLink(File(projects, "repo").toPath(), target.toPath())
        assertThat(resolver.resolve("s")).isInstanceOf(SessionWorkspace.Unavailable::class.java)
        bind("gone")
        val gone = resolver.resolve("s") as SessionWorkspace.Unavailable
        assertThat(gone.workspace).isNull()
        assertThat(gone.workspaceId).isEqualTo("gone")
    }

    @Test fun `共享目录交给渠道校验，失败时不可用`() = runBlocking<Unit> {
        records["sh"] = WorkspaceEntity("sh", WorkspaceEntity.KIND_SHARED, "共享", "/storage/emulated/0/Code/repo", 1)
        bind("sh")
        assertThat((resolver.resolve("s") as SessionWorkspace.Directory).root.path).isEqualTo("/storage/emulated/0/Code/repo")
        shared.failure = ExternalFolderException("UNSUPPORTED", "此发行渠道不支持真实路径挂载")
        val unavailable = resolver.resolve("s") as SessionWorkspace.Unavailable
        assertThat(unavailable.reason).contains("不支持")
        assertThat(SessionWorkspaceResolver.kindOf(unavailable)).isEqualTo("shared")
        assertThat(sharedRoots).containsExactly("/storage/emulated/0/Code/repo", "/storage/emulated/0/Code/repo")
    }

    @Test fun `格式无效的登记直接不可用且不访问目录`() = runBlocking<Unit> {
        records["bad"] = WorkspaceEntity("bad", WorkspaceEntity.KIND_SHARED, "坏", "relative/path", 1)
        bind("bad")
        assertThat(resolver.resolve("s")).isInstanceOf(SessionWorkspace.Unavailable::class.java)
        assertThat(sharedRoots).isEmpty()
    }
}
