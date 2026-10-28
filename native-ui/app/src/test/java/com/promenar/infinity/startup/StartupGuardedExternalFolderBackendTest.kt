package com.promenar.infinity.startup

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.ExternalMountDao
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.dao.WorkspaceDao
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.storage.ExternalFolderManager
import com.promenar.infinity.data.storage.TransferMemoryBackend
import com.promenar.infinity.data.workspace.SessionWorkspaceResolver
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupGuardedExternalFolderBackendTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @After fun clearBarrier() { StartupWriteBarrier.resetForTesting() }

    @Test
    fun `隔离挂载读取可用而所有写入原语均被拒绝`() = runTest {
        val directory = temporaryFolder.newFolder().canonicalFile
        val manager = isolatedRoot(directory)
        StartupWriteBarrier.install(manager)
        val memory = TransferMemoryBackend().apply { write("/file.txt", "保留".toByteArray()) }
        val backend = StartupGuardedExternalFolderBackend(copying(memory), "healthy") { directory }

        assertThat(backend.stat("/file.txt")).isNotNull()
        assertThat(backend.list("/")).hasSize(1)
        assertThat(String(backend.read("/file.txt"))).isEqualTo("保留")
        backend.validateTree("/")
        val writes: List<suspend () -> Unit> = listOf(
            { backend.write("/file.txt", "覆盖".toByteArray()); Unit },
            { backend.createDirectory("/new"); Unit },
            { backend.delete("/file.txt") },
            { backend.move("/file.txt", "/moved.txt") },
            { backend.copy("/file.txt", "/copied.txt") },
        )
        writes.forEach { write ->
            assertThat(runCatching { write() }.exceptionOrNull()).isInstanceOf(StartupIsolationException::class.java)
        }
        assertThat(String(memory.read("/file.txt"))).isEqualTo("保留")
        assertThat(memory.list("/").map { it.path }).containsExactly("/file.txt")
    }

    @Test
    fun `同一上级挂载可从隔离子树复制到健康子树但移动必须检查来源与目标`() = runTest {
        val parent = temporaryFolder.newFolder().canonicalFile
        val isolated = File(parent, "isolated").apply { mkdirs() }
        StartupWriteBarrier.install(isolatedRoot(isolated))
        val memory = TransferMemoryBackend().apply {
            createDirectory("/isolated"); createDirectory("/healthy")
            write("/isolated/file.txt", "来源".toByteArray())
            write("/healthy/file.txt", "健康".toByteArray())
        }
        val backend = StartupGuardedExternalFolderBackend(copying(memory), "healthy") { parent }

        backend.copy("/isolated/file.txt", "/healthy/copy.txt")
        assertThat(String(memory.read("/healthy/copy.txt"))).isEqualTo("来源")
        assertThat(runCatching { backend.move("/isolated/file.txt", "/healthy/moved.txt") }.exceptionOrNull())
            .isInstanceOf(StartupIsolationException::class.java)
        assertThat(runCatching { backend.move("/healthy/file.txt", "/isolated/moved.txt") }.exceptionOrNull())
            .isInstanceOf(StartupIsolationException::class.java)
        assertThat(runCatching { backend.copy("/healthy/file.txt", "/isolated/copied.txt") }.exceptionOrNull())
            .isInstanceOf(StartupIsolationException::class.java)
        assertThat(memory.stat("/isolated/file.txt")).isNotNull()
        assertThat(memory.stat("/healthy/file.txt")).isNotNull()
        assertThat(memory.stat("/healthy/moved.txt")).isNull()
        assertThat(memory.stat("/isolated/moved.txt")).isNull()
        assertThat(memory.stat("/isolated/copied.txt")).isNull()
    }

    @Test
    fun `无物理路径的提供商仍按隔离会话拒绝写入且允许读取`() = runTest {
        val manager = StartupIssueManager()
        manager.recover(StartupStep.SESSION_JOURNAL, StartupIssueScope(StartupScopeKind.SESSION, "session", "会话")) { error("冲突") }
        StartupWriteBarrier.install(manager)
        val memory = TransferMemoryBackend().apply { write("/file.txt", "原文".toByteArray()) }
        val backend = StartupGuardedExternalFolderBackend(memory, "session") { null }

        assertThat(String(backend.read("/file.txt"))).isEqualTo("原文")
        assertThat(runCatching { backend.write("/file.txt", byteArrayOf(1)) }.exceptionOrNull())
            .isInstanceOf(StartupIsolationException::class.java)
        assertThat(String(memory.read("/file.txt"))).isEqualTo("原文")
    }

    @Test
    fun `后端创建后每次写入读取最新隔离状态且重试成功后放行`() = runTest {
        val directory = temporaryFolder.newFolder().canonicalFile
        val manager = StartupIssueManager().apply { bindRoot("root", directory.path) }
        StartupWriteBarrier.install(manager)
        val memory = TransferMemoryBackend()
        val backend = StartupGuardedExternalFolderBackend(memory, "session") { directory }
        backend.write("/file.txt", "第一版".toByteArray())
        var broken = true
        manager.recover(StartupStep.WORKSPACE_JOURNAL, StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, "root", "工作区")) { check(!broken) }

        assertThat(runCatching { backend.write("/file.txt", "覆盖".toByteArray()) }.exceptionOrNull())
            .isInstanceOf(StartupIsolationException::class.java)
        broken = false
        manager.resolve(manager.issues.value.single().id, StartupIssueAction.RETRY)
        backend.write("/file.txt", "第二版".toByteArray())

        assertThat(String(memory.read("/file.txt"))).isEqualTo("第二版")
    }

    @Test
    fun `物理来源的符号链接别名先规范化再匹配隔离根`() = runTest {
        val parent = temporaryFolder.newFolder().canonicalFile
        val isolated = File(parent, "isolated").apply { mkdirs() }
        val alias = File(parent, "alias")
        Files.createSymbolicLink(alias.toPath(), isolated.toPath())
        StartupWriteBarrier.install(isolatedRoot(isolated))
        val memory = TransferMemoryBackend()
        val backend = StartupGuardedExternalFolderBackend(memory, "healthy") { alias }

        assertThat(runCatching { backend.write("/file.txt", byteArrayOf(1)) }.exceptionOrNull())
            .isInstanceOf(StartupIsolationException::class.java)
        assertThat(memory.stat("/file.txt")).isNull()
    }

    @Test
    fun `真实路径挂载的管理器写入经过底层隔离屏障`() = runTest {
        val directory = temporaryFolder.newFolder().canonicalFile
        StartupWriteBarrier.install(isolatedRoot(directory))
        val memory = TransferMemoryBackend().apply { write("/file.txt", "保留".toByteArray()) }
        val context = mockk<Context> { every { noBackupFilesDir } returns temporaryFolder.newFolder() }
        val dao = mockk<ExternalMountDao> {
            coEvery { visible("healthy") } returns listOf(ExternalMountEntity("mount", "docs", "real_path", directory.path, "healthy", false, 1L))
        }
        val manager = ExternalFolderManager(context, dao, mockk<IWorkspaceRepository>(), FileIndexEventSink.None) { memory }

        assertThat(String(manager.read("healthy", "/mnt/docs/file.txt"))).isEqualTo("保留")
        assertThat(runCatching { manager.write("healthy", "/mnt/docs/file.txt", "覆盖".toByteArray(), null, false) }.exceptionOrNull())
            .isInstanceOf(StartupIsolationException::class.java)
        assertThat(String(memory.read("/file.txt"))).isEqualTo("保留")
    }

    @Test
    fun `自选工作区挂载使用解析后的实际根检查隔离`() = runTest {
        val parent = temporaryFolder.newFolder().canonicalFile
        val project = File(parent, "demo").apply { mkdirs() }
        StartupWriteBarrier.install(isolatedRoot(project))
        val memory = TransferMemoryBackend().apply { write("/file.txt", "保留".toByteArray()) }
        val sessionDao = mockk<SessionDao> {
            coEvery { getById("healthy") } returns SessionEntity("healthy", "agent", workspaceId = "workspace", createdAt = 1L, updatedAt = 1L)
        }
        val workspaceDao = mockk<WorkspaceDao> {
            coEvery { getById("workspace") } returns WorkspaceEntity("workspace", WorkspaceEntity.KIND_PROJECT, "项目", "demo", 1L)
        }
        val resolver = SessionWorkspaceResolver(sessionDao, workspaceDao, parent, mockk(), projectBackend = { memory })
        val context = mockk<Context> { every { noBackupFilesDir } returns temporaryFolder.newFolder() }
        val manager = ExternalFolderManager(context, mockk(), mockk(), FileIndexEventSink.None, resolver) { memory }

        assertThat(String(manager.read("healthy", "/file.txt"))).isEqualTo("保留")
        assertThat(runCatching { manager.write("healthy", "/file.txt", "覆盖".toByteArray(), null, false) }.exceptionOrNull())
            .isInstanceOf(StartupIsolationException::class.java)
        assertThat(String(memory.read("/file.txt"))).isEqualTo("保留")
    }

    private suspend fun isolatedRoot(directory: File): StartupIssueManager = StartupIssueManager().apply {
        bindRoot("root", directory.path)
        recover(StartupStep.WORKSPACE_JOURNAL, StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, "root", "工作区")) { error("冲突") }
    }

    private fun copying(memory: TransferMemoryBackend) = object : ExternalFolderBackend by memory {
        override suspend fun copy(sourcePath: String, targetPath: String) { memory.write(targetPath, memory.read(sourcePath), createOnly = true) }
        override suspend fun move(sourcePath: String, targetPath: String) { copy(sourcePath, targetPath); memory.delete(sourcePath) }
    }
}
