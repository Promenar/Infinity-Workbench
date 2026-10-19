package com.promenar.nexara.data.workspace

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.repository.FileOperationRepository
import com.promenar.nexara.data.repository.WorkspaceDeletionTransaction
import com.promenar.nexara.data.repository.WorkspaceRepository
import com.promenar.nexara.data.session.RoomSessionDeletionTransaction
import com.promenar.nexara.data.storage.ExternalFolderManager
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.skills.CreateFileSkill
import com.promenar.nexara.ui.chat.manager.skills.FileReadSkill
import com.promenar.nexara.ui.chat.manager.skills.FileWriteSkill
import com.promenar.nexara.ui.chat.manager.skills.MountedWorkspaceSkill
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 目录浏览数据层在设备上的端到端行为：新建并登记项目、列共享卷目录、
 * 两个会话经文件工具读写同一项目文件夹，删除其中一个会话后目录完好。
 */
@RunWith(AndroidJUnit4::class)
class WorkspacePickerDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: NexaraDatabase
    private lateinit var base: File
    private lateinit var projects: File
    private lateinit var browser: WorkspaceDirectoryBrowser
    private lateinit var resolver: SessionWorkspaceResolver
    private lateinit var repository: WorkspaceRepository
    private lateinit var manager: ExternalFolderManager

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java).allowMainThreadQueries().build()
        base = File(context.filesDir, "workspace-picker-device-${UUID.randomUUID()}").apply { mkdirs() }
        projects = File(base, "projects")
        browser = WorkspaceDirectoryBrowser(projects, AndroidSharedStorageVolumes(context), db.workspaceDao())
        resolver = SessionWorkspaceResolver(db.sessionDao(), db.workspaceDao(), projects, AndroidSharedWorkspaceAccess(context))
        repository = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), deleteCommitter = WorkspaceDeletionTransaction(db)::delete)
        manager = ExternalFolderManager(context, db.externalMountDao(), repository, FileIndexEventSink.None, resolver)
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    @Test
    fun createsAndRegistersProjectFolderOnDevice() = runBlocking<Unit> {
        assertThat(browser.createProject("bad name")).isEqualTo(CreateFolderResult.Rejected(FolderNameError.INVALID))
        val created = browser.createProject("demo") as CreateFolderResult.Created
        assertThat(browser.listProjects().map { it.name }).containsExactly("demo")
        assertThat(File(projects, "demo").list()).isEmpty()

        val registered = browser.registerProject(created.folder.name)
        assertThat(browser.registerProject("demo")).isEqualTo(registered)
        val resolved = resolver.resolveWorkspace(registered.id)
        assertWithMessage(resolved.toString()).that(resolved).isInstanceOf(SessionWorkspace.Directory::class.java)
        // filesDir 位于 /data/user/0 这一系统符号链接下；项目根规范化后目录后端仍可逐级拒绝链接。
        assertThat((resolved as SessionWorkspace.Directory).root).isEqualTo(File(projects.canonicalFile, "demo"))
    }

    @Test
    fun listsSharedVolumeDirectoriesWithoutLinksOrAndroid() = runBlocking<Unit> {
        assumeTrue("此渠道不支持共享存储", browser.sharedSupported)
        assumeTrue("未授予所有文件访问权限", browser.hasSharedPermission())
        val volume = browser.sharedRoots().firstOrNull()
        assumeTrue("没有可用的共享存储卷", volume != null)
        val folders = browser.listShared(volume!!)
        assertThat(folders.map { it.name.lowercase() }).doesNotContain("android")
        folders.forEach { folder ->
            assertWithMessage(folder.path.path).that(Files.isDirectory(folder.path.toPath(), NOFOLLOW_LINKS)).isTrue()
            assertWithMessage(folder.path.path).that(Files.isSymbolicLink(folder.path.toPath())).isFalse()
        }
    }

    @Test
    fun twoSessionsShareOneProjectFolderAndDeletingOneKeepsIt() = runBlocking<Unit> {
        browser.createProject("shared-demo")
        val workspace = browser.registerProject("shared-demo")
        val now = System.currentTimeMillis()
        listOf("a", "b").forEach { id ->
            db.sessionDao().insert(
                SessionEntity(
                    id = id, agentId = "agent", workspaceId = workspace.id,
                    workspacePath = File(base, "private-$id").apply { mkdirs() }.path,
                    createdAt = now, updatedAt = now,
                ),
            )
        }
        val roots = listOf("a", "b").associateWith { repository.ensureSessionRoot(it) }
        val files = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao())
        val project = File(projects.canonicalFile, "shared-demo")

        val created = skill(CreateFileSkill(repository, files))
            .execute(args("""{"name":"notes.md","content":"来自会话 A\n"}"""), execution("a", roots.getValue("a")))
        assertWithMessage(created.content).that(created.status).isEqualTo("success")
        assertThat(File(project, "notes.md").readText()).isEqualTo("来自会话 A\n")

        val readByB = skill(FileReadSkill(files, repository))
            .execute(args("""{"path":"/notes.md"}"""), execution("b", roots.getValue("b")))
        assertWithMessage(readByB.content).that(readByB.content).contains("来自会话 A")

        val hash = Sha256Utils.hash(File(project, "notes.md").readBytes())
        val writtenByB = skill(FileWriteSkill(files, repository)).execute(
            args("""{"path":"/notes.md","content":"会话 B 已更新\n","expected_sha256":"$hash"}"""),
            execution("b", roots.getValue("b")),
        )
        assertWithMessage(writtenByB.content).that(writtenByB.status).isEqualTo("success")
        val readByA = skill(FileReadSkill(files, repository))
            .execute(args("""{"path":"/notes.md"}"""), execution("a", roots.getValue("a")))
        assertThat(readByA.content).contains("会话 B 已更新")

        assertThat(RoomSessionDeletionTransaction(db).deleteWithoutWorkspace("a")).isTrue()
        assertThat(File(project, "notes.md").readText()).isEqualTo("会话 B 已更新\n")
        assertThat(db.workspaceDao().getById(workspace.id)).isNotNull()
        assertThat(resolver.resolve("b")).isInstanceOf(SessionWorkspace.Directory::class.java)
        assertThat(db.workspaceDao().deleteIfUnreferenced(workspace.id)).isFalse()
    }

    private fun skill(original: SkillDefinition) =
        MountedWorkspaceSkill(original, manager, repository, FileOperationRepository(db.fileEntryDao(), db.fileVersionDao()), FileIndexEventSink.None)

    private fun execution(session: String, root: FileEntry) = object : SkillExecutionContext {
        override val sessionId = session
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid: String get() = root.uuid
    }

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject
}
