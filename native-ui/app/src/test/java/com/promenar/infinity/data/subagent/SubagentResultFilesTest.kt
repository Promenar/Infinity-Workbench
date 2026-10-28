package com.promenar.infinity.data.subagent

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.data.storage.RealPathExternalFolderBackend
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubagentResultFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val sessions = mockk<ISessionRepository>()
    private val workspace = mockk<IWorkspaceRepository>(relaxed = true)
    private val mounts = mockk<ExternalWorkspaceAccess>(relaxed = true)
    private val session = Session("parent", "agent", workspaceId = "project")
    private val run = SubagentRun(
        index = 0, id = "sa_123abc", description = "任务", prompt = "任务",
        toolProfile = SubagentToolProfile.READ_ONLY, modelId = "model", maxSteps = 0, startedAt = 1L,
    )

    @Test
    fun `目录工作区保存全文且恢复运行生成新文件不覆盖`() = runBlocking {
        val root = temporary.newFolder("project").canonicalFile
        val backend = real(root)
        val writer = writer({ directory(root) }, { backend })
        val text = "# 完整结论\n\n" + "正文和标点。\n".repeat(4_000)

        val first = writer.write(session, run, text)
        val second = writer.write(session, run, "新结论")

        assertThat(first).startsWith("/subagent-results/sa_123abc-")
        assertThat(first).endsWith(".md")
        assertThat(second).isNotEqualTo(first)
        assertThat(backend.read(first).toString(Charsets.UTF_8)).isEqualTo(text)
        assertThat(backend.read(second).toString(Charsets.UTF_8)).isEqualTo("新结论")
        coVerify(exactly = 0) { workspace.ensureSessionRoot(any()) }
    }

    @Test
    fun `Linux目录工作区返回可由文件工具读取的workspace路径`() = runBlocking {
        val root = temporary.newFolder("linux-project").canonicalFile
        val backend = real(root)
        val writer = writer({ directory(root) }, { backend }, SessionRunMode.LINUX)

        val path = writer.write(session, run, "Linux结论")

        assertThat(path).startsWith("/workspace/subagent-results/")
        assertThat(backend.read(path.removePrefix("/workspace")).toString(Charsets.UTF_8)).isEqualTo("Linux结论")
    }

    @Test
    fun `工作区不可用时拒绝保存且不回退私有根`() = runBlocking {
        val writer = writer({ SessionWorkspace.Unavailable("project", null, "失效") }, { error("不能构造后端") })

        val failure = failure { writer.write(session, run, "结论") }

        assertThat(failure).isInstanceOf(ExternalFolderException::class.java)
        coVerify(exactly = 0) { workspace.ensureSessionRoot(any()) }
    }

    @Test
    fun `工作区身份在创建目录后改变则拒绝写入旧根`() = runBlocking {
        val root = temporary.newFolder("old").canonicalFile
        val next = temporary.newFolder("next").canonicalFile
        var current = directory(root)
        val real = real(root)
        val backend = object : ExternalFolderBackend by real {
            override suspend fun createDirectory(path: String) = real.createDirectory(path).also {
                current = directory(next, "next")
            }
        }
        val writer = writer({ current }, { backend })

        assertThat(failure { writer.write(session, run, "结论") }).isInstanceOf(ExternalFolderException::class.java)
        assertThat(File(root, "subagent-results").listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `结论目录符号链接不会将写入导出父工作区`() = runBlocking {
        val root = temporary.newFolder("project").canonicalFile
        val outside = temporary.newFolder("outside").canonicalFile
        Files.createSymbolicLink(File(root, "subagent-results").toPath(), outside.toPath())
        val writer = writer({ directory(root) }, { real(root) })

        assertThat(failure { writer.write(session, run, "结论") }).isNotNull()
        assertThat(outside.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `非法子代理标识被拒绝`() = runBlocking {
        val writer = writer({ error("不能解析工作区") }, { error("不能构造后端") })
        assertThat(failure { writer.write(session, run.copy(id = "../../outside"), "结论") })
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `私有工作区经仓库流式写入完整字节并确认索引`() = runBlocking {
        val root = temporary.newFolder("private").canonicalFile
        val rootEntry = entry("root", "/", true, root)
        val parent = entry("results", "/subagent-results", true, root)
        val text = "中文完整结论\n".repeat(2_000)
        var bytes = byteArrayOf()
        var created: FileEntry? = null
        coEvery { sessions.getById(session.id) } returns session.copy(workspaceRootUuid = "root", workspaceId = null)
        coEvery { workspace.getByUuid("root", "root") } returns rootEntry
        coEvery { workspace.getByMaterializedPath("root", "/subagent-results") } returns parent
        coEvery { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val output = ByteArrayOutputStream()
            arg<(OutputStream) -> Unit>(7)(output)
            bytes = output.toByteArray()
            entry(arg(1), arg(5), false, root).copy(hash = "hash", sizeBytes = bytes.size.toLong()).also { created = it }
        }
        val events = mutableListOf<FileIndexEvent>()
        val writer = SubagentResultFiles(sessions, workspace, mounts, { SessionWorkspace.Private }, temporary.root,
            FileIndexEventSink { events += it })

        val path = writer.write(session, run, text)

        assertThat(path).startsWith("/session/subagent-results/")
        assertThat(bytes.toString(Charsets.UTF_8)).isEqualTo(text)
        assertThat(events.single()).isInstanceOf(FileIndexEvent.Changed::class.java)
        coVerify(exactly = 1) { workspace.confirmCreatedEntry("root", created!!.uuid) }
    }

    @Test
    fun `私有工作区索引提交失败回滚已创建文件`() = runBlocking {
        val root = temporary.newFolder("private-rollback").canonicalFile
        coEvery { sessions.getById(session.id) } returns session.copy(workspaceRootUuid = "root", workspaceId = null)
        coEvery { workspace.getByUuid("root", "root") } returns entry("root", "/", true, root)
        coEvery { workspace.getByMaterializedPath("root", "/subagent-results") } returns entry("results", "/subagent-results", true, root)
        coEvery { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) } returns entry("new", "/subagent-results/new.md", false, root)
        val events = mutableListOf<FileIndexEvent>()
        val writer = SubagentResultFiles(sessions, workspace, mounts, { SessionWorkspace.Private }, temporary.root,
            FileIndexEventSink { events += it; if (it is FileIndexEvent.Changed) error("索引不可用") })

        assertThat(failure { writer.write(session, run, "结论") }).isNotNull()
        coVerify(exactly = 1) { workspace.rollbackCreatedEntry("root", "new") }
        coVerify(exactly = 0) { workspace.confirmCreatedEntry(any(), any()) }
        assertThat(events.last()).isInstanceOf(FileIndexEvent.Deleted::class.java)
    }

    @Test
    fun `私有主根在目录查询期间切到自选目录则在任何创建前拒绝`() = runBlocking {
        val root = temporary.newFolder("private-switch").canonicalFile
        val project = temporary.newFolder("new-project").canonicalFile
        var current: SessionWorkspace = SessionWorkspace.Private
        coEvery { sessions.getById(session.id) } returns session.copy(workspaceRootUuid = "root", workspaceId = null)
        coEvery { workspace.getByUuid("root", "root") } returns entry("root", "/", true, root)
        coEvery { workspace.getByMaterializedPath("root", "/subagent-results") } coAnswers {
            current = directory(project)
            null
        }
        val writer = SubagentResultFiles(sessions, workspace, mounts, { current }, temporary.root,
            directoryBackend = { real(it.root) })

        assertThat(failure { writer.write(session, run, "完整结论") }).isInstanceOf(ExternalFolderException::class.java)
        coVerify(exactly = 0) { workspace.createDirectoryInWorkspace(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertThat(project.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `执行模式在目录查询期间变化则在任何创建前拒绝`() = runBlocking {
        val root = temporary.newFolder("private-mode-switch").canonicalFile
        var mode = SessionRunMode.BUILTIN
        coEvery { sessions.getById(session.id) } returns session.copy(workspaceRootUuid = "root", workspaceId = null)
        coEvery { workspace.getByUuid("root", "root") } returns entry("root", "/", true, root)
        coEvery { workspace.getByMaterializedPath("root", "/subagent-results") } coAnswers {
            mode = SessionRunMode.LINUX
            null
        }
        val writer = SubagentResultFiles(sessions, workspace, mounts, { SessionWorkspace.Private }, temporary.root,
            modeFor = { mode })

        assertThat(failure { writer.write(session, run, "完整结论") }).isInstanceOf(ExternalFolderException::class.java)
        coVerify(exactly = 0) { workspace.createDirectoryInWorkspace(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun writer(
        current: suspend (String) -> SessionWorkspace,
        backend: (SessionWorkspace.Directory) -> ExternalFolderBackend,
        mode: SessionRunMode = SessionRunMode.BUILTIN,
    ) = SubagentResultFiles(sessions, workspace, mounts, current, temporary.root,
        modeFor = { mode }, directoryBackend = backend)

    private fun directory(root: File, id: String = "project") = SessionWorkspace.Directory(
        WorkspaceEntity(id, WorkspaceEntity.KIND_PROJECT, "项目", root.name, 1L), root,
    )

    private fun real(root: File) = RealPathExternalFolderBackend(root, emptySet(), { true }, JvmBoundRealPathFileSystem())

    private fun entry(uuid: String, path: String, directory: Boolean, root: File) = FileEntry(
        uuid = uuid, workspaceRootUuid = "root", parentUuid = null, name = path.substringAfterLast('/'),
        hash = "hash", isDirectory = directory, physicalRootPath = root.path, materializedPath = path,
        createdAt = 1L, updatedAt = 1L,
    )

    private suspend fun failure(block: suspend () -> Unit): Exception? = try {
        block()
        null
    } catch (failure: Exception) { failure }
}
