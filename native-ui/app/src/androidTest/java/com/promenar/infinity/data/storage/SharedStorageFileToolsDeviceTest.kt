package com.promenar.infinity.data.storage

import android.os.Environment
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.FileOperationRepository
import com.promenar.infinity.data.repository.WorkspaceRepository
import com.promenar.infinity.data.workspace.AndroidSharedWorkspaceAccess
import com.promenar.infinity.data.workspace.SessionWorkspaceResolver
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.CreateFileSkill
import com.promenar.infinity.ui.chat.manager.skills.CreateDirectorySkill
import com.promenar.infinity.ui.chat.manager.skills.FileWriteSkill
import com.promenar.infinity.ui.chat.manager.skills.FilePatchSkill
import com.promenar.infinity.ui.chat.manager.skills.MoveFileSkill
import com.promenar.infinity.ui.chat.manager.skills.DeleteFileSkill
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.skills.LinuxSessionExternalWorkspaceAccess
import com.promenar.infinity.ui.chat.manager.skills.MountedWorkspaceSkill
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 使用共享存储真实 FUSE 后端，不以应用私有目录代替卷兼容性验收。 */
@RunWith(AndroidJUnit4::class)
class SharedStorageFileToolsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var backend: com.promenar.infinity.domain.storage.ExternalFolderBackend

    @Before fun setUp() {
        assertTrue("设备验收必须授予所有文件访问权限", RealPathMountSupport.hasPermission(context))
        @Suppress("DEPRECATION")
        val volume = Environment.getExternalStorageDirectory()
        directory = File(volume, "Download/InfinityStorageTest-${UUID.randomUUID()}").apply { check(mkdirs()) }
        backend = RealPathMountSupport.createBackend(context, directory.path)
    }

    @After fun tearDown() {
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun createsAndReadsFileAndDirectoryOnSharedStorage() = runBlocking {
        backend.createDirectory("/notes")
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        backend.write("/notes/中文.bin", bytes, createOnly = true)
        assertArrayEquals(bytes, backend.read("/notes/中文.bin"))
        assertEquals(listOf("中文.bin"), backend.list("/notes").map { it.name })
        assertEquals(listOf(".gitignore"), File(directory, ".infinity").list()!!.toList())
    }

    @Test fun existingTargetIsNeverOverwritten() = runBlocking {
        File(directory, "file.txt").writeText("other-writer")
        expectCode("CONFLICT") { backend.write("/file.txt", "ours".toByteArray(), createOnly = true) }
        assertEquals("other-writer", File(directory, "file.txt").readText())
    }

    @Test fun privateDirectoryRetainsAtomicFileLifecycle() = runBlocking {
        val root = File(context.filesDir, "private-file-tools-${UUID.randomUUID()}").canonicalFile.apply { check(mkdirs()) }
        try {
            val privateBackend = RealPathExternalFolderBackend(root)
            privateBackend.createDirectory("/tree")
            val original = privateBackend.write("/tree/file", "original".toByteArray(), createOnly = true)
            privateBackend.write("/tree/file", "changed".toByteArray(), original.sha256)
            privateBackend.move("/tree", "/moved")
            assertEquals("changed", File(root, "moved/file").readText())
            privateBackend.copy("/moved", "/copy")
            privateBackend.delete("/moved")
            privateBackend.delete("/copy")
            assertTrue(privateBackend.list("/").isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun overwritesSharedFileWithCompleteContent() = runBlocking {
        File(directory, "file.txt").writeText("original")
        val hash = backend.stat("/file.txt")!!.sha256
        backend.write("/file.txt", "changed".toByteArray(), hash)
        assertEquals("changed", File(directory, "file.txt").readText())
    }

    @Test fun movesAndRenamesSharedFile() = runBlocking {
        File(directory, "file.txt").writeText("original")
        backend.createDirectory("/destination")
        backend.move("/file.txt", "/destination/file.txt")
        backend.move("/destination/file.txt", "/destination/renamed.txt")
        assertEquals("original", File(directory, "destination/renamed.txt").readText())
        assertFalse(File(directory, "file.txt").exists())
        assertFalse(File(directory, "destination/file.txt").exists())
    }

    @Test fun movesSharedDirectoryWithNestedContent() = runBlocking {
        File(directory, "tree/nested").mkdirs()
        File(directory, "tree/nested/child").writeText("child")
        backend.createDirectory("/destination")
        backend.move("/tree", "/destination/renamed")
        assertEquals("child", File(directory, "destination/renamed/nested/child").readText())
        assertFalse(File(directory, "tree").exists())
    }

    @Test fun deletesSharedFile() = runBlocking {
        File(directory, "file.txt").writeText("original")
        backend.delete("/file.txt", backend.stat("/file.txt")!!.sha256)
        assertFalse(File(directory, "file.txt").exists())
    }

    @Test fun recursivelyDeletesSharedDirectory() = runBlocking {
        File(directory, "tree/nested").mkdirs()
        File(directory, "tree/nested/child").writeText("child")
        backend.delete("/tree")
        assertFalse(File(directory, "tree").exists())
    }

    @Test fun linuxAndNativeFileToolsCompleteSharedStorageLifecycle() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        val privateBase = File(context.filesDir, "shared-file-tools-${UUID.randomUUID()}").canonicalFile.apply { check(mkdirs()) }
        try {
            val repository = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao())
            val files = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao())
            val now = System.currentTimeMillis()
            db.workspaceDao().register(WorkspaceEntity("shared", WorkspaceEntity.KIND_SHARED, "手机存储", directory.path, now))
            db.sessionDao().insert(SessionEntity(id = "shared-session", agentId = "agent", workspacePath = privateBase.path,
                workspaceId = "shared", createdAt = now, updatedAt = now))
            val root = repository.ensureSessionRoot("shared-session")
            val resolver = SessionWorkspaceResolver(db.sessionDao(), db.workspaceDao(), File(privateBase, "projects"), AndroidSharedWorkspaceAccess(context))
            val manager = ExternalFolderManager(context, db.externalMountDao(), repository, FileIndexEventSink.None, resolver)
            val rootfs = File(privateBase, "rootfs").apply { mkdir() }
            val linux = LinuxSessionExternalWorkspaceAccess(manager, repository, files, resolver::resolve, RealPathExternalFolderBackend(rootfs))
            val execution = object : SkillExecutionContext {
                override val sessionId = "shared-session"
                override val agentId = "agent"
                override val workspacePath: String? = null
                override val workspaceRootUuid = root.uuid
            }
            for ((isLinux, access) in listOf(false to manager, true to linux)) {
                val prefix = if (isLinux) "linux" else "native"
                val parent = if (isLinux) "/workspace" else "/"
                val filePath = parent.trimEnd('/') + "/$prefix.md"
                val folderPath = parent.trimEnd('/') + "/$prefix-folder"
                suspend fun execute(definition: SkillDefinition, arguments: String) {
                    val tool = MountedWorkspaceSkill(definition, access, repository, files,
                        FileIndexEventSink.None, linuxPaths = isLinux)
                    val result = tool.execute(Json.parseToJsonElement(arguments).jsonObject, execution)
                    assertEquals("$prefix ${definition.id}: ${result.content}", "success", result.status)
                }
                execute(CreateDirectorySkill(repository), """{"name":"$prefix-folder","parentPath":"$parent"}""")
                execute(CreateFileSkill(repository, files), """{"name":"$prefix.md","parentPath":"$parent","content":"shared"}""")
                assertEquals("shared", File(directory, "$prefix.md").readText())
                val writtenHash = backend.stat("/$prefix.md")!!.sha256
                execute(FileWriteSkill(files, repository), """{"path":"$filePath","content":"written","expected_sha256":"$writtenHash"}""")
                assertEquals("written", File(directory, "$prefix.md").readText())
                val patchHash = backend.stat("/$prefix.md")!!.sha256
                execute(FilePatchSkill(files, repository), """{"path":"$filePath","expected_sha256":"$patchHash","operations":[{"action":"replace_lines","startLine":1,"endLine":1,"expectedContent":"written","newContent":"patched"}]}""")
                assertEquals("patched", File(directory, "$prefix.md").readText())
                execute(MoveFileSkill(repository, FileIndexEventSink.None), """{"path":"$filePath","newParentPath":"$folderPath"}""")
                assertFalse(File(directory, "$prefix.md").exists())
                assertEquals("patched", File(directory, "$prefix-folder/$prefix.md").readText())
                execute(MoveFileSkill(repository, FileIndexEventSink.None), """{"path":"$folderPath/$prefix.md","newName":"renamed.md"}""")
                assertFalse(File(directory, "$prefix-folder/$prefix.md").exists())
                assertEquals("patched", File(directory, "$prefix-folder/renamed.md").readText())
                execute(DeleteFileSkill(repository), """{"path":"$folderPath/renamed.md","irreversible":true}""")
                assertFalse(File(directory, "$prefix-folder/renamed.md").exists())
                execute(CreateDirectorySkill(repository), """{"name":"nested","parentPath":"$folderPath"}""")
                execute(CreateFileSkill(repository, files), """{"name":"child.md","parentPath":"$folderPath/nested","content":"child"}""")
                execute(MoveFileSkill(repository, FileIndexEventSink.None), """{"path":"$folderPath","newName":"$prefix-moved"}""")
                assertFalse(File(directory, "$prefix-folder").exists())
                assertEquals("child", File(directory, "$prefix-moved/nested/child.md").readText())
                execute(DeleteFileSkill(repository), """{"path":"${parent.trimEnd('/')}/$prefix-moved","irreversible":true}""")
                assertFalse(File(directory, "$prefix-moved").exists())
                assertFalse(File(privateBase, "$prefix.md").exists())
                assertFalse(File(rootfs, "workspace/$prefix.md").exists())
            }
        } finally {
            db.close()
            privateBase.deleteRecursively()
        }
    }

    private suspend fun expectCode(code: String, action: suspend () -> Unit) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("应返回 $code，实际为 $failure", failure is ExternalFolderException)
        assertEquals(code, (failure as ExternalFolderException).code)
    }
}
