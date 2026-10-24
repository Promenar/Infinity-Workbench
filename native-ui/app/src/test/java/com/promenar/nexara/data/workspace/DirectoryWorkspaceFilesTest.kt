package com.promenar.nexara.data.workspace

import android.content.Context
import com.promenar.nexara.data.local.db.dao.ExternalMountDao
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.dao.WorkspaceDao
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.session.SessionExecutionGate
import com.promenar.nexara.data.storage.ExternalFolderManager
import com.promenar.nexara.data.storage.JvmBoundRealPathFileSystem
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.storage.ExternalWorkspaceAccess
import com.promenar.nexara.domain.workspace.DirectoryFileOpCode
import com.promenar.nexara.domain.workspace.DirectoryFileOpException
import io.mockk.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectoryWorkspaceFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(): Pair<File, ExternalWorkspaceAccess> {
        val root = temporary.newFolder().canonicalFile
        val row = WorkspaceEntity("project", "project", "项目", "project", 1)
        val sessions = mockk<SessionDao>()
        val workspaces = mockk<WorkspaceDao>()
        coEvery { sessions.getById("s") } returns mockk { every { workspaceId } returns "project" }
        coEvery { workspaces.getById("project") } returns row
        val resolver = SessionWorkspaceResolver(sessions, workspaces, root.parentFile, mockk())
        coEvery { workspaces.getById("project") } returns row.copy(location = root.name)
        val dao = mockk<ExternalMountDao>()
        coEvery { dao.visible(any()) } returns emptyList()
        val context = mockk<Context>()
        every { context.noBackupFilesDir } returns temporary.newFolder()
        val backend = JvmBoundRealPathFileSystem().backend(root)
        val manager = ExternalFolderManager(context, dao, mockk<IWorkspaceRepository>(), FileIndexEventSink.None, resolver) { backend }
        return root to manager
    }

    @Test fun `改名移动复制和删除使用同一挂载且目录可从回收区恢复`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        files.createDirectory("s", "/", "source")
        File(root, "source/a.md").writeText("内容")
        assertEquals("/source/b.md", files.rename("s", "//source/a.md", "b.md").path)
        files.createDirectory("s", "/", "target")
        assertEquals(listOf("/source/b.md"), files.move("s", listOf("/source/b.md"), "/target").succeeded)
        assertFalse(File(root, "source/b.md").exists())
        assertEquals(listOf("/target/b.md"), files.copy("s", listOf("/target/b.md"), "/target").succeeded)
        files.copy("s", listOf("/target/b.md"), "/target")
        assertEquals("内容", File(root, "target/b (2).md").readText())
        assertEquals("内容", File(root, "target/b (3).md").readText())
        assertEquals(listOf("/target"), files.delete("s", listOf("/target")).succeeded)
        assertFalse(File(root, "target").exists())
        mounts.restore("s", mounts.recycleItems("s").single().id)
        assertEquals("内容", File(root, "target/b (3).md").readText())
    }

    @Test fun `单项失败继续执行且不覆盖同名文件`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        File(root, "a").writeText("源")
        File(root, "b").writeText("目标")
        expect(DirectoryFileOpCode.NAME_EXISTS) { files.rename("s", "/a", "b") }
        expect(DirectoryFileOpCode.NAME_INVALID) { files.rename("s", "/a", "../b") }
        expect(DirectoryFileOpCode.NOT_FOUND) { files.rename("s", "/missing", "c") }
        val result = files.delete("s", listOf("/missing", "/../outside", "/a"))
        assertEquals(listOf("/a"), result.succeeded)
        assertEquals(mapOf("/missing" to DirectoryFileOpCode.NOT_FOUND, "/../outside" to DirectoryFileOpCode.NAME_INVALID), result.failed)
        assertEquals("目标", File(root, "b").readText())
        files.createDirectory("s", "/", "target")
        File(root, "target/b").writeText("已有")
        assertEquals(DirectoryFileOpCode.NAME_EXISTS, files.move("s", listOf("/b"), "/target").failed["/b"])
    }

    @Test fun `目录递归复制且目录点文件和无扩展名按完整名称避让`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        File(root, "notes.md/nested").mkdirs()
        File(root, "notes.md/nested/a").writeText("递归内容")
        File(root, ".env").writeText("点文件")
        File(root, "README").writeText("无扩展名")
        val result = files.copy("s", listOf("/notes.md", "/.env", "/README"), "/")
        assertTrue(result.failed.isEmpty())
        assertEquals("递归内容", File(root, "notes.md (2)/nested/a").readText())
        assertEquals("点文件", File(root, ".env (2)").readText())
        assertEquals("无扩展名", File(root, "README (2)").readText())
    }

    @Test fun `拒绝目录自身子目录符号链接及独立挂载命名空间`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        File(root, "dir/child").mkdirs()
        for (target in listOf("/dir", "/dir/child")) {
            assertEquals(DirectoryFileOpCode.TARGET_INSIDE_SOURCE, files.move("s", listOf("/dir"), target).failed["/dir"])
            assertEquals(DirectoryFileOpCode.TARGET_INSIDE_SOURCE, files.copy("s", listOf("/dir"), target).failed["/dir"])
        }
        Files.createSymbolicLink(File(root, "dir/link").toPath(), temporary.newFolder().toPath())
        assertEquals(DirectoryFileOpCode.NAME_INVALID, files.copy("s", listOf("/dir"), "/").failed["/dir"])
        assertEquals(DirectoryFileOpCode.NAME_INVALID, files.delete("s", listOf("/dir/link")).failed["/dir/link"])
        expect(DirectoryFileOpCode.NAME_INVALID) { files.createDirectory("s", "/mnt/other", "x") }
        expect(DirectoryFileOpCode.NAME_INVALID) { files.createDirectory("s", "/skills", "x") }
        assertTrue(File(root, "dir/child").exists())
    }

    @Test fun `不可用只读忙碌和意外异常转换为契约错误`() = runBlocking {
        val (_, mounts) = fixture()
        val mount = mounts.workspaceMount("s")!!
        val override = mockk<ExternalWorkspaceAccess>()
        coEvery { override.workspaceMount("s") } returns null
        val files = DefaultDirectoryWorkspaceFiles(override, SessionExecutionGate()) { false }
        expect(DirectoryFileOpCode.WORKSPACE_UNAVAILABLE) { files.createDirectory("s", "/", "x") }
        coEvery { override.workspaceMount("s") } returns mount.copy(unavailableReason = "离线")
        expect(DirectoryFileOpCode.WORKSPACE_UNAVAILABLE) { files.createDirectory("s", "/", "x") }
        coEvery { override.workspaceMount("s") } returns mount.copy(readOnly = true)
        expect(DirectoryFileOpCode.READ_ONLY) { files.createDirectory("s", "/", "x") }
        coEvery { override.workspaceMount("s") } returns mount
        expect(DirectoryFileOpCode.SESSION_BUSY) {
            DefaultDirectoryWorkspaceFiles(override, SessionExecutionGate()) { true }.rename("s", "/a", "b")
        }
        every { override.backend(any(), any()) } throws java.io.IOException("读写失败")
        expect(DirectoryFileOpCode.IO) { files.createDirectory("s", "/", "x") }
    }

    private suspend fun expect(code: DirectoryFileOpCode, block: suspend () -> Any?) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue(error.toString(), error is DirectoryFileOpException)
        assertEquals(code, (error as DirectoryFileOpException).code)
    }
}
