package com.promenar.nexara.data.canvas

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.rag.FileIndexEvent
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.repository.FileOperationRepository
import com.promenar.nexara.data.repository.SessionRepository
import com.promenar.nexara.data.repository.TestWorkspaceFileOps
import com.promenar.nexara.data.repository.WorkspaceDeletionTransaction
import com.promenar.nexara.data.repository.WorkspaceRepository
import com.promenar.nexara.data.storage.ExternalFolderManager
import com.promenar.nexara.data.storage.JvmBoundRealPathFileSystem
import com.promenar.nexara.data.storage.RealPathExternalFolderBackend
import com.promenar.nexara.data.storage.isUserSkills
import com.promenar.nexara.data.storage.isWorkspaceRoot
import com.promenar.nexara.data.workspace.SessionWorkspaceResolver
import com.promenar.nexara.data.workspace.SharedWorkspaceAccess
import com.promenar.nexara.data.workspace.WorkspaceFileTargetResolver
import com.promenar.nexara.domain.repository.PatchOperation
import com.promenar.nexara.domain.repository.PatchResult
import com.promenar.nexara.data.session.BranchSessionUseCase
import com.promenar.nexara.data.session.BranchSessionResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class WorkspaceCanvasFilesTest {
    private lateinit var db: NexaraDatabase
    private lateinit var directory: File
    private lateinit var privateRoot: File
    private lateinit var workspace: WorkspaceRepository
    private lateinit var operations: FileOperationRepository
    private lateinit var roots: SessionWorkspaceResolver
    private lateinit var mounts: ExternalFolderManager
    private lateinit var store: FileCanvasStore
    private val events = mutableListOf<FileIndexEvent>()
    private val index = FileIndexEventSink { events.add(it) }
    private val fs = JvmBoundRealPathFileSystem()

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory("canvas-workspace-test").toFile().canonicalFile
        privateRoot = File(directory, "private")
        val skills = File(directory, "skills").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java).allowMainThreadQueries().build()
        db.sessionDao().insert(SessionEntity(id = "s", agentId = "a", workspacePath = privateRoot.path, createdAt = 1, updatedAt = 1))
        workspace = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), defaultWorkspaceParent = File(directory, "private-roots"), fileOps = TestWorkspaceFileOps(), deleteCommitter = WorkspaceDeletionTransaction(db)::delete)
        operations = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), TestWorkspaceFileOps(), indexEventSink = index)
        roots = SessionWorkspaceResolver(db.sessionDao(), db.workspaceDao(), File(directory, "projects"), object : SharedWorkspaceAccess {
            override fun root(location: String) = File(location)
            override fun backend(root: File) = real(root)
        }, ::real)
        mounts = ExternalFolderManager(context, db.externalMountDao(), workspace, index, roots, skills) { mount ->
            when {
                mount.isUserSkills -> real(skills)
                mount.isWorkspaceRoot -> real(File(directory, "projects/project"))
                mount.sourceType == "real_path" -> real(File(mount.sourceId))
                else -> null
            }
        }
        store = FileCanvasStore(File(directory, "history"), workspaceFiles = WorkspaceCanvasFiles(
            WorkspaceFileTargetResolver(SessionRepository(db.sessionDao(), db.messageDao()), workspace, mounts, roots::resolve, skills),
            workspace, operations, mounts, index, fs,
        ))
    }

    private fun real(root: File) = RealPathExternalFolderBackend(root, emptySet(), { true }, fs)

    private suspend fun directoryRoot(): File {
        val root = File(directory, "projects/project").apply { mkdirs() }
        db.workspaceDao().insert(WorkspaceEntity("project-id", WorkspaceEntity.KIND_PROJECT, "项目", "project", 1))
        db.sessionDao().update(db.sessionDao().getById("s")!!.copy(workspaceId = "project-id"))
        return root
    }

    @After fun cleanup() {
        db.close()
        directory.deleteRecursively()
    }

    @Test fun `私有根按需建立文件树更新索引并保持BOM及CRLF字节`() = runBlocking {
        val first = store.create("s", "图表", "\uFEFF<p>一</p>\r\n", "m")
        assertThat(first.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        val path = "/${first.metadata.workspacePath}"
        val root = workspace.ensureSessionRoot("s")
        val entry = workspace.getByMaterializedPath(root.uuid, path)!!
        assertThat(entry.mimeType).isEqualTo("text/html")
        assertThat(workspace.getByMaterializedPath(root.uuid, "/canvas")?.isDirectory).isTrue()
        assertThat(File(privateRoot, path.trimStart('/')).readBytes()).isEqualTo(first.html.toByteArray())
        val updated = store.update("s", first.metadata.id, html = "\uFEFF<p>二</p>\r\n")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(File(privateRoot, path.trimStart('/')).readBytes()).isEqualTo(updated.html.toByteArray())
        val indexed = workspace.getByMaterializedPath(root.uuid, path)!!
        assertThat(indexed.uuid).isEqualTo(entry.uuid)
        assertThat(indexed.hash).isEqualTo(canvasSha256(updated.html.toByteArray()))
        assertThat(events.filterIsInstance<FileIndexEvent.Changed>().map { it.fileUuid }).contains(entry.uuid)
        store.deleteSession("s")
        assertThat(File(privateRoot, path.trimStart('/')).readText()).isEqualTo(updated.html)
        assertThat(workspace.getByMaterializedPath(root.uuid, path)?.uuid).isEqualTo(entry.uuid)
    }

    @Test fun `空HTML也能在私有根新建和更新`() = runBlocking {
        val first = store.create("s", "空画布", "", "m")
        assertThat(first.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        val file = File(privateRoot, first.metadata.workspacePath!!)
        assertThat(file.isFile).isTrue()
        assertThat(file.length()).isEqualTo(0)
        store.update("s", first.metadata.id, html = "内容")
        val empty = store.update("s", first.metadata.id, html = "")
        assertThat(empty.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(file.length()).isEqualTo(0)
    }

    @Test fun `私有根一MiB内多行HTML更新不受文件工具行数预算限制`() = runBlocking {
        val first = store.create("s", "多行", "<pre>${"\n".repeat(20_001)}</pre>", "m")
        assertThat(first.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        val updated = store.update("s", first.metadata.id, html = "<pre>${"\n".repeat(20_002)}更新</pre>")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(File(privateRoot, updated.metadata.workspacePath!!).readBytes()).isEqualTo(updated.html.toByteArray())
        val compact = store.update("s", first.metadata.id, html = "<p>简短</p>")
        assertThat(compact.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
    }

    @Test fun `文件工具补丁导入为新版本再以最新内容应用画布补丁`() = runBlocking {
        val first = store.create("s", "图表", "<p>原文</p>", "m")
        val root = workspace.ensureSessionRoot("s")
        val path = "/${first.metadata.workspacePath}"
        val entry = workspace.getByMaterializedPath(root.uuid, path)!!
        val patch = operations.patchFile(root.uuid, entry.uuid, listOf(PatchOperation("replace_lines", 1, 1, newContent = "<p>外部</p>")), entry.hash)
        assertThat(patch).isInstanceOf(PatchResult.Success::class.java)
        val updated = store.update("s", first.metadata.id, patches = listOf(CanvasPatch("外部", "画布")))
        assertThat(updated.version).isEqualTo(3)
        assertThat(store.read("s", first.metadata.id, 2)?.html).isEqualTo("<p>外部</p>")
        assertThat(updated.html).isEqualTo("<p>画布</p>")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
    }

    @Test fun `私有根文件移入回收站后只标记缺失更新重新创建索引`() = runBlocking {
        val first = store.create("s", "图表", "原文", "m")
        val root = workspace.ensureSessionRoot("s")
        val path = "/${first.metadata.workspacePath}"
        val entry = workspace.getByMaterializedPath(root.uuid, path)!!
        workspace.moveToRecycleBin(root.uuid, entry.uuid)
        assertThat(store.syncFromWorkspace("s", first.metadata.id)!!.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.MISSING)
        assertThat(workspace.getByMaterializedPath(root.uuid, path)).isNull()
        val updated = store.update("s", first.metadata.id, html = "更新")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(workspace.getByMaterializedPath(root.uuid, path)?.uuid).isNotEqualTo(entry.uuid)
    }

    @Test fun `目录根处理文件名冲突外部修改和删除且不落入私有文件树`() = runBlocking {
        val root = directoryRoot()
        File(root, "canvas").mkdirs()
        File(root, "canvas/图表.html").writeText("保留")
        val first = store.create("s", "图表", "原文", "m")
        assertThat(first.metadata.workspacePath).isEqualTo("canvas/图表-2.html")
        assertThat(first.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        val file = File(root, first.metadata.workspacePath!!)
        file.writeText("外部\r\n")
        assertThat(store.syncFromWorkspace("s", first.metadata.id)?.html).isEqualTo("外部\r\n")
        assertThat(store.read("s", first.metadata.id)?.version).isEqualTo(2)
        file.delete()
        assertThat(store.syncFromWorkspace("s", first.metadata.id)!!.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.MISSING)
        assertThat(file.exists()).isFalse()
        val updated = store.update("s", first.metadata.id, html = "更新")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(file.readText()).isEqualTo("更新")
        assertThat(db.sessionDao().getById("s")!!.workspaceRootUuid).isNull()
        store.deleteSession("s")
        assertThat(file.readText()).isEqualTo("更新")
        assertThat(File(root, "canvas/图表.html").readText()).isEqualTo("保留")
    }

    @Test fun `工作区不可用不回退私有根且恢复后重试写出`() = runBlocking {
        val root = directoryRoot()
        root.delete()
        val first = store.create("s", "标题", "完整私有源码", "m")
        assertThat(first.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.FAILED)
        assertThat(store.read("s", first.metadata.id, 1)?.html).isEqualTo("完整私有源码")
        assertThat(db.sessionDao().getById("s")!!.workspaceRootUuid).isNull()
        root.mkdirs()
        val synced = store.syncFromWorkspace("s", first.metadata.id)!!
        assertThat(synced.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(File(root, synced.metadata.workspacePath!!).readText()).isEqualTo("完整私有源码")
    }

    @Test fun `目录根分支保留工作区绑定且画布写到新路径不覆盖源文件`() = runBlocking {
        val root = directoryRoot()
        db.messageDao().insertAll(listOf(MessageEntity(id = "m", sessionId = "s", role = "assistant", content = "图表", status = "success", createdAt = 1)))
        val first = store.create("s", "图表", "源内容", "m")
        var serial = 0
        val result = BranchSessionUseCase(db, newId = { "branch-${serial++}" }, canvasStore = store).branch("s", "m") as BranchSessionResult.Success
        val session = db.sessionDao().getById(result.sessionId)!!
        assertThat(session.workspaceId).isEqualTo(db.sessionDao().getById("s")!!.workspaceId)
        val copied = store.read(result.sessionId, first.metadata.id)!!
        assertThat(copied.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.NOT_WRITTEN)
        assertThat(copied.metadata.workspacePath).isNull()
        val synced = store.syncFromWorkspace(result.sessionId, first.metadata.id)!!
        assertThat(synced.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(synced.metadata.workspacePath).isEqualTo("canvas/图表-2.html")
        val branchFile = File(root, synced.metadata.workspacePath!!)
        assertThat(branchFile.readText()).isEqualTo("源内容")
        store.update(result.sessionId, first.metadata.id, html = "分支内容")
        assertThat(File(root, first.metadata.workspacePath!!).readText()).isEqualTo("源内容")
        assertThat(branchFile.readText()).isEqualTo("分支内容")
    }
}
