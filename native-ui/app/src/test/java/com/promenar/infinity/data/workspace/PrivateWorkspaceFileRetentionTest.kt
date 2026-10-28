package com.promenar.infinity.data.workspace

import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.data.storage.BoundRealPathDirectory
import com.promenar.infinity.data.storage.BoundRealPathWrite
import com.promenar.infinity.data.storage.RealPathFileSystem
import com.promenar.infinity.data.local.db.dao.WorkspaceDao
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.domain.workspace.PrivateWorkspaceSummary
import io.mockk.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateWorkspaceFileRetentionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val clock = Clock.fixed(Instant.parse("2026-10-24T08:09:00Z"), ZoneOffset.UTC)

    @Test fun `只有链接与特殊文件时无摘要也不创建保留目录`() = runBlocking {
        val source = temporary.newFolder().canonicalFile
        java.nio.file.Files.createSymbolicLink(File(source, "link").toPath(), temporary.newFolder().toPath())
        assertEquals(0, ProcessBuilder("mkfifo", File(source, "pipe").path).start().waitFor())
        val projects = temporary.newFolder().canonicalFile
        val rows = mutableListOf<WorkspaceEntity>()
        val retention = PrivateWorkspaceFileRetention(projects, dao(rows), clock, JvmBoundRealPathFileSystem())
        assertNull(retention.summary(source.toPath()))
        assertNull(retention.prepare(source.toPath(), "标题"))
        assertTrue(projects.list()!!.isEmpty())
        assertTrue(rows.isEmpty())
    }

    @Test fun `保留副本内新增链接时拒绝登记并保留人工核查证据`() = runBlocking {
        val source = temporary.newFolder().canonicalFile
        File(source, "a").writeText("内容")
        val projects = temporary.newFolder().canonicalFile
        val rows = mutableListOf<WorkspaceEntity>()
        val retention = PrivateWorkspaceFileRetention(projects, dao(rows), clock, JvmBoundRealPathFileSystem())
        val kept = retention.prepare(source.toPath(), "标题")!!
        val destination = projects.listFiles()!!.single()
        java.nio.file.Files.createSymbolicLink(File(destination, "link").toPath(), source.toPath())
        assertNotNull(runCatching { kept.register() }.exceptionOrNull())
        assertNotNull(runCatching { kept.rollback() }.exceptionOrNull())
        assertTrue(rows.isEmpty())
        assertEquals("内容", File(destination, "a").readText())
        assertEquals("内容", File(source, "a").readText())
    }

    @Test fun `统计和保留跳过链接与特殊文件且不读取界外内容`() = runBlocking {
        val source = temporary.newFolder().canonicalFile
        File(source, "nested").mkdir()
        File(source, "nested/a").writeText("abc")
        val outside = temporary.newFolder().canonicalFile
        File(outside, "secret").writeText("界外文件")
        java.nio.file.Files.createSymbolicLink(File(source, "link").toPath(), outside.toPath())
        java.nio.file.Files.createSymbolicLink(File(source, "nested/broken").toPath(), File(outside, "missing").toPath())
        val fifo = File(source, "pipe")
        assertEquals(0, ProcessBuilder("mkfifo", fifo.path).start().waitFor())
        val projects = temporary.newFolder().canonicalFile
        val rows = mutableListOf<WorkspaceEntity>()
        val fs = JvmBoundRealPathFileSystem()
        val retention = PrivateWorkspaceFileRetention(projects, dao(rows), clock, fs)
        assertEquals(PrivateWorkspaceSummary(1, 3), retention.summary(source.toPath()))
        val kept = retention.prepare(source.toPath(), "标题")!!
        kept.register()
        val destination = File(projects, rows.single().location)
        assertEquals("abc", File(destination, "nested/a").readText())
        assertEquals(setOf("nested", "nested/a"), destination.walkTopDown().drop(1).map { destination.toPath().relativize(it.toPath()).toString() }.toSet())
        assertFalse(fs.reads.any { it.startsWith(outside.toPath()) || it == fifo.toPath() })
        kept.rollback()
        assertTrue(projects.list()!!.isEmpty())
        assertEquals("界外文件", File(outside, "secret").readText())
    }

    @Test fun `统计与保留排除回收站版本和暂存且空标题回退并避让登记和目录`() = runBlocking {
        val source = temporary.newFolder().canonicalFile
        File(source, "nested").mkdir()
        File(source, "nested/a.txt").writeText("abc")
        listOf(".recycle_bin", ".infinity_versions", ".infinity-staging").forEach {
            File(source, it).mkdir()
            File(source, "$it/old").writeText("历史")
        }
        val projects = temporary.newFolder().canonicalFile
        File(projects, "session-20261024-0809").mkdir()
        val rows = mutableListOf(WorkspaceEntity("old", "project", "历史登记", "session-20261024-0809-2", 1))
        val dao = dao(rows)
        val retention = PrivateWorkspaceFileRetention(projects, dao, clock, JvmBoundRealPathFileSystem())
        assertEquals(PrivateWorkspaceSummary(1, 3), retention.summary(source.toPath()))
        val kept = retention.prepare(source.toPath(), "  ")!!
        assertEquals(1, rows.size)
        kept.register()
        assertEquals("session-20261024-0809-3", kept.displayName)
        assertEquals("abc", File(projects, "${kept.displayName}/nested/a.txt").readText())
        assertEquals(listOf("nested"), File(projects, kept.displayName).list()!!.toList())
        assertEquals(kept.workspaceId, rows.last().id)
        kept.rollback()
        assertEquals(1, rows.size)
        assertFalse(File(projects, kept.displayName).exists())
        assertEquals("abc", File(source, "nested/a.txt").readText())
    }

    @Test fun `登记失败回滚目录且保留来源`() = runBlocking {
        val source = temporary.newFolder().canonicalFile
        File(source, "a").writeText("源文件")
        val projects = temporary.newFolder().canonicalFile
        val rows = mutableListOf<WorkspaceEntity>()
        val dao = dao(rows)
        coEvery { dao.insert(any()) } coAnswers {
            rows += firstArg<WorkspaceEntity>()
            throw java.io.IOException("登记失败")
        }
        val retention = PrivateWorkspaceFileRetention(projects, dao, clock, JvmBoundRealPathFileSystem())
        val kept = retention.prepare(source.toPath(), " 标题 ")!!
        assertNotNull(runCatching { kept.register() }.exceptionOrNull())
        kept.rollback()
        assertTrue(rows.isEmpty())
        assertTrue(projects.list()!!.isEmpty())
        assertEquals("源文件", File(source, "a").readText())
    }

    @Test fun `只有历史文件时无摘要且不创建登记`() = runBlocking {
        val source = temporary.newFolder().canonicalFile
        File(source, ".recycle_bin").mkdir()
        File(source, ".recycle_bin/a").writeText("回收文件")
        val projects = temporary.newFolder().canonicalFile
        val rows = mutableListOf<WorkspaceEntity>()
        val retention = PrivateWorkspaceFileRetention(projects, dao(rows), clock, JvmBoundRealPathFileSystem())
        assertNull(retention.summary(source.toPath()))
        assertNull(retention.prepare(source.toPath(), "标题"))
        assertTrue(rows.isEmpty())
        assertTrue(projects.list()!!.isEmpty())
    }

    @Test fun `复制中途读取失败会清理部分副本且来源不变`() = runBlocking {
        val source = temporary.newFolder().canonicalFile
        File(source, "a").writeText("第一份")
        File(source, "b").writeText("第二份")
        val projects = temporary.newFolder().canonicalFile
        val rows = mutableListOf<WorkspaceEntity>()
        val fs = JvmBoundRealPathFileSystem().apply {
            beforeOperation = { operation, path ->
                if (operation == "read" && path == File(source, "b").toPath()) throw java.io.IOException("读取失败")
            }
        }
        val retention = PrivateWorkspaceFileRetention(projects, dao(rows), clock, fs)
        assertNotNull(runCatching { retention.prepare(source.toPath(), "标题") }.exceptionOrNull())
        assertTrue(rows.isEmpty())
        assertTrue(projects.list()!!.isEmpty())
        assertEquals("第一份", File(source, "a").readText())
        assertEquals("第二份", File(source, "b").readText())
    }

    @Test fun `复制前祖先被替换成链接时拒绝读取界外文件`() = runBlocking {
        val source = temporary.newFolder().canonicalFile
        File(source, "nested").mkdir()
        File(source, "nested/a").writeText("来源")
        val outside = temporary.newFolder().canonicalFile
        File(outside, "a").writeText("界外")
        val projects = temporary.newFolder().canonicalFile
        var opens = 0
        val fs = JvmBoundRealPathFileSystem().apply {
            beforeOperation = { operation, path ->
                if (operation == "directory" && path == File(source, "nested").toPath() && ++opens == 2) {
                    java.nio.file.Files.move(path, File(source, "saved").toPath())
                    java.nio.file.Files.createSymbolicLink(path, outside.toPath())
                }
            }
        }
        val retention = PrivateWorkspaceFileRetention(projects, dao(mutableListOf()), clock, fs)
        assertNotNull(runCatching { retention.prepare(source.toPath(), "标题") }.exceptionOrNull())
        assertTrue(projects.list()!!.isEmpty())
        assertEquals("界外", File(outside, "a").readText())
        assertEquals("来源", File(source, "saved/a").readText())
    }

    @Test fun `副本根被替换时回滚保留替换目录与原副本`() = runBlocking {
        val source = temporary.newFolder().canonicalFile
        File(source, "a").writeText("来源")
        val projects = temporary.newFolder().canonicalFile
        val retention = PrivateWorkspaceFileRetention(projects, dao(mutableListOf()), clock, JvmBoundRealPathFileSystem())
        val kept = retention.prepare(source.toPath(), "标题")!!
        val destination = projects.listFiles()!!.single()
        val saved = File(projects, "saved")
        java.nio.file.Files.move(destination.toPath(), saved.toPath())
        destination.mkdir()
        File(destination, "other").writeText("他人文件")
        assertNotNull(runCatching { kept.rollback() }.exceptionOrNull())
        assertEquals("他人文件", File(destination, "other").readText())
        assertEquals("来源", File(saved, "a").readText())
    }

    @Test fun `副本被其它登记采用时回滚不删除新增文件`() = runBlocking {
        val source = temporary.newFolder().canonicalFile
        File(source, "a").writeText("来源")
        val projects = temporary.newFolder().canonicalFile
        val rows = mutableListOf<WorkspaceEntity>()
        val retention = PrivateWorkspaceFileRetention(projects, dao(rows), clock, JvmBoundRealPathFileSystem())
        val kept = retention.prepare(source.toPath(), "标题")!!
        val destination = projects.listFiles()!!.single()
        rows += WorkspaceEntity("other", "project", "已采用", destination.name, 1)
        File(destination, "other").writeText("新增")
        assertNotNull(runCatching { kept.rollback() }.exceptionOrNull())
        assertEquals("新增", File(destination, "other").readText())
        assertEquals("other", rows.single().id)
    }

    @Test fun `登记前副本内容变化时拒绝采用`() = runBlocking {
        val source = temporary.newFolder().canonicalFile
        File(source, "a").writeText("来源")
        val projects = temporary.newFolder().canonicalFile
        val rows = mutableListOf<WorkspaceEntity>()
        val retention = PrivateWorkspaceFileRetention(projects, dao(rows), clock, JvmBoundRealPathFileSystem())
        val kept = retention.prepare(source.toPath(), "标题")!!
        File(projects.listFiles()!!.single(), "a").writeText("变化")
        assertNotNull(runCatching { kept.register() }.exceptionOrNull())
        assertTrue(rows.isEmpty())
        assertEquals("来源", File(source, "a").readText())
    }

    @Test fun `回滚整树检查后叶子被替换时保留新文件`() = runBlocking {
        val source = temporary.newFolder().canonicalFile
        File(source, "a").writeText("来源")
        val projects = temporary.newFolder().canonicalFile
        val fs = JvmBoundRealPathFileSystem()
        val retention = PrivateWorkspaceFileRetention(projects, dao(mutableListOf()), clock, fs)
        val kept = retention.prepare(source.toPath(), "标题")!!
        var injected = false
        fs.beforeOperation = { operation, path ->
            if (operation == "delete" && path.fileName.toString() == "a" && !injected) {
                injected = true
                java.nio.file.Files.move(path, path.resolveSibling("saved"))
                path.toFile().writeText("新文件")
            }
        }
        assertNotNull(runCatching { kept.rollback() }.exceptionOrNull())
        val remaining = projects.walkTopDown().filter { it.isFile }.map { it.readText() }.toSet()
        assertTrue(remaining.containsAll(listOf("来源", "新文件")))
    }

    @Test fun `写入关闭后被替换的文件不能成为有效副本`() = runBlocking {
        val source = temporary.newFolder().canonicalFile
        File(source, "a").writeText("来源")
        val projects = temporary.newFolder().canonicalFile
        val base = JvmBoundRealPathFileSystem()
        fun wrap(directory: BoundRealPathDirectory): BoundRealPathDirectory = object : BoundRealPathDirectory by directory {
            override fun openDirectory(name: String) = wrap(directory.openDirectory(name))
            override fun createFile(name: String): BoundRealPathWrite {
                val output = directory.createFile(name)
                return object : BoundRealPathWrite by output {
                    override fun close() {
                        output.close()
                        directory.atomicReplace(name, "saved")
                        directory.createFile(name).use { it.output.write("替换文件".toByteArray()) }
                    }
                }
            }
        }
        val fs = object : RealPathFileSystem {
            override fun openFileSystemRoot() = wrap(base.openFileSystemRoot())
        }
        val rows = mutableListOf<WorkspaceEntity>()
        val retention = PrivateWorkspaceFileRetention(projects, dao(rows), clock, fs)
        assertNotNull(runCatching { retention.prepare(source.toPath(), "标题") }.exceptionOrNull())
        assertTrue(rows.isEmpty())
        assertEquals("来源", File(source, "a").readText())
        assertTrue(projects.walkTopDown().filter { it.isFile }.map { it.readText() }.toSet().containsAll(listOf("来源", "替换文件")))
    }

    private fun dao(rows: MutableList<WorkspaceEntity>): WorkspaceDao = mockk {
        coEvery { getByLocation(any(), any()) } answers {
            rows.firstOrNull { it.kind == firstArg<String>() && it.location == secondArg<String>() }
        }
        coEvery { insert(any()) } coAnswers { rows += firstArg<WorkspaceEntity>(); Unit }
        coEvery { countSessionReferences(any()) } returns 0
        coEvery { countAgentReferences(any()) } returns 0
        coEvery { deleteById(any()) } answers { if (rows.removeAll { it.id == firstArg<String>() }) 1 else 0 }
    }
}
