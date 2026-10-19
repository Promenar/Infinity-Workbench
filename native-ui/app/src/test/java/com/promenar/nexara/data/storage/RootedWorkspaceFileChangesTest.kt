package com.promenar.nexara.data.storage

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.ExternalMountDao
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.dao.WorkspaceDao
import com.promenar.nexara.data.local.db.entity.ExternalMountEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.model.FileChangeType
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.workspace.SessionWorkspaceResolver
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.ui.chat.diff.ApprovalDiffPreview
import com.promenar.nexara.ui.chat.diff.ApprovalDiffPreviewLoader
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.skills.*
import com.promenar.nexara.ui.common.diff.FileChangeKind
import com.promenar.nexara.ui.common.diff.FileDiffContent
import io.mockk.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Test

/** 自选目录工作区（rooted）与 `/mnt` 挂载上的改动摘要采集和审批差异预览；私有工作区与 Room 一律不被触碰。 */
class RootedWorkspaceFileChangesTest {
    private val directory = Files.createTempDirectory("rooted-changes").toFile().canonicalFile
    private val projects = File(directory, "projects")
    private val repo = File(projects, "demo").apply { mkdirs() }
    private val context = mockk<Context> { every { noBackupFilesDir } returns File(directory, "no-backup") }
    private val sessionDao = mockk<SessionDao> {
        coEvery { getById("session") } answers { SessionEntity("session", "agent", workspaceId = "ws-1", createdAt = 1, updatedAt = 1) }
    }
    private val workspaceDao = mockk<WorkspaceDao> {
        coEvery { getById(any()) } returns WorkspaceEntity("ws-1", WorkspaceEntity.KIND_PROJECT, "演示仓库", "demo", 1)
    }
    private val fileSystem = JvmBoundRealPathFileSystem()
    private val resolver = SessionWorkspaceResolver(sessionDao, workspaceDao, projects, mockk(), projectBackend = { fileSystem.backend(it) })
    private val rows = MutableStateFlow(listOf(ExternalMountEntity("mount", "termux", "saf_tree", "content://test/tree/root", "session", false, 1)))
    private val mountDao = mockk<ExternalMountDao> {
        coEvery { visible(any()) } answers { rows.value.filter { it.scopeKey.isEmpty() || it.scopeKey == firstArg<String>() } }
        every { observeVisible(any()) } answers { val id = firstArg<String>(); rows.map { list -> list.filter { it.scopeKey == id } } }
        coEvery { all() } answers { rows.value }
        coEvery { update(any()) } just Runs
    }
    private val memory = TransferMemoryBackend()
    private val workspace = mockk<IWorkspaceRepository>()
    private val files = mockk<IFileOperationRepository>()
    private val manager = ExternalFolderManager(context, mountDao, workspace, FileIndexEventSink.None, resolver) { mount ->
        if (mount.isWorkspaceRoot) null else memory
    }
    private val execution = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspaceRootUuid = "root"
        override val workspacePath: String? = null
    }

    @After fun cleanup() { directory.deleteRecursively() }

    private fun skill(original: SkillDefinition) = MountedWorkspaceSkill(original, manager, workspace, files, FileIndexEventSink.None)
    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun hash(file: File) = Sha256Utils.hash(file.readBytes())

    @Test fun `自选目录的文件工具成功后附带改动摘要且不改变模型文本`() = runBlocking<Unit> {
        val created = skill(CreateFileSkill(workspace, files)).execute(args("""{"name":"a.txt","content":"one\ntwo\n"}"""), execution)
        assertThat(created.content).startsWith("已创建 /a.txt")
        val createdChange = created.fileChanges!!.single()
        assertThat(createdChange.change).isEqualTo(FileChangeType.CREATED)
        assertThat(createdChange.path).isEqualTo("/a.txt")
        assertThat(createdChange.added).isEqualTo(2)

        val saved = skill(FileWriteSkill(files, workspace)).execute(
            args("""{"path":"/a.txt","content":"one\nTWO\nthree\n","expected_sha256":"${hash(File(repo, "a.txt"))}"}"""), execution,
        )
        assertThat(saved.content).isEqualTo("已保存：/a.txt\nsha256: ${hash(File(repo, "a.txt"))}")
        val modified = saved.fileChanges!!.single()
        assertThat(modified.change).isEqualTo(FileChangeType.MODIFIED)
        assertThat(modified.added to modified.removed).isEqualTo(2 to 1)
        assertThat(modified.hunks!!.single().lines).containsExactly(" one", "-two", "+TWO", "+three").inOrder()

        val patched = skill(FilePatchSkill(files, workspace)).execute(
            args("""{"path":"/a.txt","expected_sha256":"${hash(File(repo, "a.txt"))}","operations":[{"action":"delete_lines","startLine":1,"endLine":1}]}"""), execution,
        )
        assertThat(patched.status).isEqualTo("success")
        assertThat(patched.fileChanges!!.single().removed).isEqualTo(1)

        val conflict = skill(FileWriteSkill(files, workspace)).execute(
            args("""{"path":"/a.txt","content":"x","expected_sha256":"${"0".repeat(64)}"}"""), execution,
        )
        assertThat(conflict.status).isEqualTo("error")
        assertThat(conflict.fileChanges).isNull()

        File(repo, "docs").mkdirs()
        val moved = skill(MoveFileSkill(workspace, FileIndexEventSink.None)).execute(args("""{"path":"/a.txt","newParentPath":"/docs"}"""), execution)
        val movedChange = moved.fileChanges!!.single()
        assertThat(movedChange.change).isEqualTo(FileChangeType.MOVED)
        assertThat(movedChange.fromPath).isEqualTo("/a.txt")
        assertThat(movedChange.path).isEqualTo("/docs/a.txt")
        assertThat(movedChange.added to movedChange.removed).isEqualTo(0 to 0)

        val deleted = skill(DeleteFileSkill(workspace)).execute(args("""{"path":"/docs/a.txt"}"""), execution)
        val deletedChange = deleted.fileChanges!!.single()
        assertThat(deletedChange.change).isEqualTo(FileChangeType.DELETED)
        assertThat(deletedChange.removed).isEqualTo(2)
        confirmVerified(workspace, files)
    }

    @Test fun `挂载路径的新建与写入同样附带改动摘要`() = runBlocking<Unit> {
        val created = skill(CreateFileSkill(workspace, files)).execute(args("""{"parentPath":"/mnt/termux","name":"m.txt","content":"mounted"}"""), execution)
        assertThat(created.fileChanges!!.single().path).isEqualTo("/mnt/termux/m.txt")
        val written = skill(FileWriteSkill(files, workspace)).execute(
            args("""{"path":"/mnt/termux/m.txt","content":"mounted\nmore","expected_sha256":"${Sha256Utils.hash(memory.read("/m.txt"))}"}"""), execution,
        )
        assertThat(written.status).isEqualTo("success")
        assertThat(written.fileChanges!!.single().added).isEqualTo(1)
        confirmVerified(workspace, files)
    }

    @Test fun `审批差异预览读取自选目录当前内容且不写入`() = runBlocking<Unit> {
        File(repo, "a.txt").writeText("v1\nkeep\n")
        val before = hash(File(repo, "a.txt"))
        val loader = ApprovalDiffPreviewLoader(workspace, manager)

        val write = loader.load("write_file", args("""{"path":"/a.txt","content":"v2\nkeep\n"}"""), "session", "root") as ApprovalDiffPreview.Diff
        assertThat(write.model.changeKind).isEqualTo(FileChangeKind.MODIFIED)
        assertThat(write.model.countsLabel).isEqualTo("+1 −1")

        val patch = loader.load(
            "patch_file",
            args("""{"path":"/a.txt","operations":[{"action":"insert_after","afterLine":2,"newContent":"tail"}]}"""),
            "session", "root",
        ) as ApprovalDiffPreview.Diff
        assertThat(patch.model.countsLabel).isEqualTo("+1 −0")
        assertThat((patch.model.content as FileDiffContent.Lines).lines.last().content).isEqualTo("tail")

        val deletion = loader.load("delete_file", args("""{"path":"/a.txt"}"""), "session", "root")
        assertThat(deletion).isEqualTo(ApprovalDiffPreview.Deletion(2))

        val create = loader.load("create_file", args("""{"name":"n.md","content":"# 标题\n正文"}"""), "session", "root") as ApprovalDiffPreview.Diff
        assertThat(create.model.countsLabel).isEqualTo("+2")

        // 补丁无法应用、文件不存在或二进制时回退到文本预览。
        assertThat(loader.load("patch_file", args("""{"path":"/a.txt","operations":[{"action":"delete_lines","startLine":9,"endLine":9}]}"""), "session", "root")).isNull()
        assertThat(loader.load("write_file", args("""{"path":"/missing.txt","content":"x"}"""), "session", "root")).isNull()
        File(repo, "b.bin").writeBytes(byteArrayOf(1, 0, 2))
        assertThat(loader.load("delete_file", args("""{"path":"/b.bin"}"""), "session", "root")).isNull()

        assertThat(hash(File(repo, "a.txt"))).isEqualTo(before)
        confirmVerified(workspace, files)
    }
}
