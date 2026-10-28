package com.promenar.infinity.data.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.WorkspaceDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/** 知识库“工作区”分组：只读浏览、单文件加入知识库、只改登记的重命名与删除。 */
class WorkspaceLibraryTest {
    private val workspace = WorkspaceEntity("ws", WorkspaceEntity.KIND_PROJECT, "演示", "demo", 1)
    private val files = linkedMapOf(
        "/" to entry("/", "", directory = true),
        "/docs" to entry("/docs", "docs", directory = true),
        "/docs/guide.md" to entry("/docs/guide.md", "guide.md", size = 7),
        "/app.bin" to entry("/app.bin", "app.bin", size = 3),
    )
    private val contents = mutableMapOf("/docs/guide.md" to "# 指南\n".toByteArray(), "/app.bin" to byteArrayOf(0, 1, 2))
    private var statCalls = 0
    private var mutateAfterRead = false
    private val writes = mutableListOf<String>()
    private val backend = object : ExternalFolderBackend {
        override suspend fun stat(path: String): ExternalFolderEntry? {
            statCalls++
            val base = files[path] ?: return null
            return if (mutateAfterRead && statCalls > 1) base.copy(lastModified = base.lastModified + 1) else base
        }
        override suspend fun list(path: String) = files.values.filter { it.path != path && File(it.path).parent == path }
        override suspend fun read(path: String, maxBytes: Long) = contents.getValue(path)
        override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry {
            writes += path
            error("知识库分组不应写入工作区")
        }
        override suspend fun createDirectory(path: String): ExternalFolderEntry { writes += path; error("只读") }
        override suspend fun delete(path: String, expectedSha256: String?) { writes += path; error("只读") }
    }
    private var resolved: SessionWorkspace = SessionWorkspace.Directory(workspace, File("/projects/demo"))
    private val dao = mockk<WorkspaceDao>(relaxed = true)
    private val created = ByteArrayOutputStream()
    private val knowledge = mockk<IWorkspaceRepository>(relaxed = true) {
        coEvery { createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (args[7] as (OutputStream) -> Unit)(created)
            mockk<FileEntry> {
                every { hash } returns "hash-1"
                every { updatedAt } returns 99L
            }
        }
    }
    private val events = mutableListOf<FileIndexEvent>()
    private val library = WorkspaceLibrary(
        workspaceDao = dao,
        resolveWorkspace = { resolved },
        backendFor = { backend },
        knowledge = knowledge,
        indexEvents = FileIndexEventSink { events += it },
        io = Dispatchers.Unconfined,
    )

    private fun entry(path: String, name: String, directory: Boolean = false, size: Long = 0) =
        ExternalFolderEntry(path, name, directory, size, lastModified = 1000)

    @Test fun `浏览与预览只读取，不写入目录`() = runBlocking<Unit> {
        assertThat(library.list("ws", "/").map { it.name }).containsExactly("docs", "app.bin")
        assertThat(library.list("ws", "/docs").map { it.name }).containsExactly("guide.md")
        assertThat(String(library.read("ws", "/docs/guide.md", 1024))).isEqualTo("# 指南\n")
        assertThat(writes).isEmpty()
    }

    @Test fun `单个文件复制进知识库根目录并发布索引事件`() = runBlocking<Unit> {
        val uuid = library.importToKnowledge("ws", "/docs/guide.md", "kb-root")
        assertThat(String(created.toByteArray())).isEqualTo("# 指南\n")
        coVerify {
            knowledge.createFileInWorkspaceStreaming("kb-root", uuid, match { it.endsWith("-guide.md") }, "text/markdown", "kb-root", match { it.startsWith("/workspace-") }, any(), any())
            knowledge.confirmCreatedEntry("kb-root", uuid)
        }
        assertThat(events).containsExactly(FileIndexEvent.Changed("kb-root", uuid, "hash-1", 99L))
        assertThat(writes).isEmpty()
    }

    @Test fun `目录、不支持的类型与读取期间变化的文件不能加入知识库`() = runBlocking<Unit> {
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { library.importToKnowledge("ws", "/docs", "kb-root") }
        }.code).isEqualTo("IS_DIRECTORY")
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { library.importToKnowledge("ws", "/app.bin", "kb-root") }
        }.code).isEqualTo("UNSUPPORTED")
        mutateAfterRead = true
        statCalls = 0
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { library.importToKnowledge("ws", "/docs/guide.md", "kb-root") }
        }.code).isEqualTo("CONFLICT")
        coVerify(exactly = 0) { knowledge.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertThat(events).isEmpty()
    }

    @Test fun `确认失败时回滚新建的知识库文件`() = runBlocking<Unit> {
        coEvery { knowledge.confirmCreatedEntry(any(), any()) } throws IllegalStateException("确认失败")
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { library.importToKnowledge("ws", "/docs/guide.md", "kb-root") }
        }
        assertThat(failure).hasMessageThat().contains("确认失败")
        coVerify { knowledge.rollbackCreatedEntry("kb-root", any()) }
    }

    @Test fun `工作区不可用时拒绝浏览且不回退`() = runBlocking<Unit> {
        resolved = SessionWorkspace.Unavailable("ws", workspace, "项目文件夹不存在")
        val failure = assertThrows(ExternalFolderException::class.java) { runBlocking { library.list("ws", "/") } }
        assertThat(failure.code).isEqualTo("UNAVAILABLE")
        assertThat(failure.message).isEqualTo("项目文件夹不存在")
    }

    @Test fun `删除只删登记：仍被引用时保留并报告引用数`() = runBlocking<Unit> {
        coEvery { dao.getById("ws") } returns workspace
        coEvery { dao.deleteIfUnreferenced("ws") } returns false
        coEvery { dao.countSessionReferences("ws") } returns 2
        coEvery { dao.countAgentReferences("ws") } returns 1
        assertThat(library.delete("ws")).isEqualTo(WorkspaceDeleteResult.Referenced(2, 1))

        coEvery { dao.deleteIfUnreferenced("ws") } returns true
        assertThat(library.delete("ws")).isEqualTo(WorkspaceDeleteResult.Deleted)
        coEvery { dao.getById("gone") } returns null
        assertThat(library.delete("gone")).isEqualTo(WorkspaceDeleteResult.Missing)
        assertThat(writes).isEmpty()
    }

    @Test fun `重命名去除首尾空白并拒绝空名`() = runBlocking<Unit> {
        coEvery { dao.rename("ws", "新名字") } returns 1
        assertThat(library.rename("ws", "  新名字 ")).isTrue()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { library.rename("ws", "   ") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { library.rename("ws", "x".repeat(81)) } }
    }
}
