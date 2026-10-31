package com.promenar.infinity.data.storage

import android.app.Application
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.domain.storage.ExternalFolderException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class RealPathSharedStorageMoveTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun driftingFileSystem() = JvmBoundRealPathFileSystem().apply {
        emulateFuse = true
        supportAtomicMoves = false
        readStateAfterRead = { _, state -> state.copy(revision = "${state.revision}:ctime-drift",
            contentVersion = "${state.contentVersion}:mtime-drift") }
    }

    @Test fun `FUSE句柄EXDEV后路径重试保持文件和目录inode且不复制`() = runBlocking {
        for (directory in listOf(false, true)) for (target in listOf("/renamed", "/notes/moved")) {
            val root = temporaryFolder.newFolder().canonicalFile
            val source = File(root, "source")
            if (directory) { source.mkdir(); File(source, "child").writeText("original") }
            else source.writeText("original")
            File(root, "notes").mkdir()
            val id = java.nio.file.Files.getAttribute(source.toPath(), "unix:ino")
            val fs = driftingFileSystem().apply {
                renameAcrossVolumes = { _, _ -> true }; pathRenameAcrossVolumes = false
            }
            fs.backend(root).move("/source", target)
            assertEquals(1, fs.pathRenameCalls)
            assertFalse(source.exists())
            assertEquals(id, java.nio.file.Files.getAttribute(File(root, target.removePrefix("/")).toPath(), "unix:ino"))
            assertTrue("rename 不应读取文件内容", fs.reads.isEmpty())
        }
    }

    @Test fun `FUSE路径父目录身份不一致时拒绝重试和复制`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "source").writeText("original")
        val fs = driftingFileSystem().apply {
            renameAcrossVolumes = { _, _ -> true }; pathRenameAcrossVolumes = false; pathParentMismatch = true
        }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull()
        assertEquals("CONFLICT", (failure as ExternalFolderException).code)
        assertEquals(0, fs.pathRenameCalls)
        assertEquals("original", File(root, "source").readText())
        assertFalse(File(root, "target").exists())
        assertTrue(fs.reads.isEmpty())
    }

    @Test fun `路径重命名已提交但来源名并发重建时保留双方并报告恢复路径`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "source").writeText("original")
        val fs = driftingFileSystem().apply { renameAcrossVolumes = { _, _ -> true }; pathRenameAcrossVolumes = false }
        fs.afterOperation = { operation, _ ->
            if (operation == "path-move") File(root, "source").writeText("external")
        }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull() as ExternalFolderException
        assertEquals("RECOVERY_REQUIRED", failure.code)
        assertTrue(failure.message!!.contains("/source"))
        assertTrue(failure.message!!.contains("/target"))
        assertEquals("external", File(root, "source").readText())
        assertEquals("original", File(root, "target").readText())
        assertTrue(fs.reads.isEmpty())
    }

    @Test fun `路径重命名返回后外层复核期间目标消失时报告恢复双路径`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "source").writeText("original")
        val target = File(root, "target")
        val fs = driftingFileSystem().apply { renameAcrossVolumes = { _, _ -> true }; pathRenameAcrossVolumes = false }
        var committed = false
        var targetChecks = 0
        fs.afterOperation = { operation, _ -> if (operation == "path-move") committed = true }
        fs.beforeOperation = { operation, path ->
            if (committed && operation == "stat" && path == target.toPath() && ++targetChecks == 2) check(target.delete())
        }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull() as ExternalFolderException
        assertEquals("RECOVERY_REQUIRED", failure.code)
        assertTrue(failure.message!!.contains("/source"))
        assertTrue(failure.message!!.contains("/target"))
        assertTrue(fs.reads.isEmpty())
    }

    @Test fun `两次EXDEV流式移动超过32MiB文件和超过千项目录`() = runBlocking {
        for (directory in listOf(false, true)) {
            val root = temporaryFolder.newFolder().canonicalFile
            val source = File(root, "source")
            if (directory) {
                source.mkdir(); repeat(1005) { File(source, "item-$it").writeText("item-$it") }
            } else source.outputStream().use { output ->
                val block = ByteArray(64 * 1024) { (it % 251).toByte() }
                repeat(4097) { output.write(block) }
            }
            fun hash(file: File): String = file.inputStream().use { input ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            val expected = if (directory) "" else hash(source)
            val fs = driftingFileSystem().apply { renameAcrossVolumes = { _, _ -> true } }
            fs.backend(root).move("/source", "/target")
            assertEquals(1, fs.pathRenameCalls)
            assertFalse(source.exists())
            val target = File(root, "target")
            if (directory) {
                assertEquals(1005, target.list()!!.size)
                repeat(1005) { assertEquals("item-$it", File(target, "item-$it").readText()) }
            } else { assertEquals(4097L * 65536, target.length()); assertEquals(expected, hash(target)) }
        }
    }

    @Test fun `目标卷空间不足在创建目标前拒绝`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "source").writeText("original")
        val fs = driftingFileSystem().apply { renameAcrossVolumes = { _, _ -> true }; availableSpace = 7 }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull()
        assertEquals("NO_SPACE", (failure as ExternalFolderException).code)
        assertEquals("original", File(root, "source").readText())
        assertFalse(File(root, "target").exists())
        assertTrue("空间检查前不读取内容", fs.reads.isEmpty())
    }

    @Test fun `源解除链接后取消从目标流式恢复并只保留源`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val fs = driftingFileSystem().apply { renameAcrossVolumes = { _, _ -> true } }
        var deleted = false
        var recoveryReads = 0
        fs.afterOperation = { operation, path ->
            if (operation == "delete" && path == source.toPath()) {
                deleted = true
                throw kotlinx.coroutines.CancellationException("源删除后取消")
            }
        }
        fs.beforeOperation = { operation, path ->
            if (deleted && operation == "read" && path == File(root, "target").toPath()) recoveryReads++
        }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue("补偿必须从目标读取", recoveryReads > 0)
        assertEquals("original", source.readText())
        assertFalse(File(root, "target").exists())
    }

    @Test fun `实际协程取消在源解除链接后完成补偿`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val fs = driftingFileSystem().apply { renameAcrossVolumes = { _, _ -> true } }
        lateinit var moving: kotlinx.coroutines.Job
        fs.afterOperation = { operation, path ->
            if (operation == "delete" && path == source.toPath()) moving.cancel()
        }
        moving = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            fs.backend(root).move("/source", "/target")
        }
        moving.start()
        moving.join()
        assertTrue(moving.isCancelled)
        assertEquals("original", source.readText())
        assertFalse(File(root, "target").exists())
    }

    @Test fun `部分源删除后目标被修改则保留数据并要求恢复`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "source").mkdir()
        File(root, "source/a").writeText("first")
        File(root, "source/b").writeText("second")
        val fs = driftingFileSystem().apply { renameAcrossVolumes = { _, _ -> true } }
        fs.beforeOperation = { operation, path ->
            if (operation == "delete" && path == File(root, "source/b").toPath()) {
                File(root, "target/a").writeText("other")
                throw IOException("第二项删除失败且目标变化")
            }
        }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull() as ExternalFolderException
        assertEquals("RECOVERY_REQUIRED", failure.code)
        assertTrue(failure.message!!.contains("/source"))
        assertTrue(failure.message!!.contains("/target"))
        assertEquals("other", File(root, "target/a").readText())
        assertEquals("second", File(root, "source/b").readText())
    }

    @Test fun `读后时间戳漂移不阻断新建覆盖补丁读取与普通移动`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val fs = driftingFileSystem()
        val backend = fs.backend(root)
        val created = backend.write("/file", "original".toByteArray(), createOnly = true)
        val written = backend.write("/file", "written".toByteArray(), created.sha256)
        val patch = String(backend.read("/file")).replace("written", "patched")
        backend.write("/file", patch.toByteArray(), written.sha256)
        backend.createDirectory("/notes")
        backend.move("/file", "/notes/file")
        backend.move("/notes/file", "/notes/renamed")
        assertEquals("patched", File(root, "notes/renamed").readText())
        assertFalse(File(root, "file").exists())
        assertFalse(File(root, "notes/file").exists())
        assertEquals(listOf(".gitignore"), File(root, ".infinity").list()!!.toList())
    }

    @Test fun `同目录EXDEV与时间戳漂移同时出现时只留下移动目标`() = runBlocking {
        for (target in listOf("/renamed", "/notes/moved")) {
            val root = temporaryFolder.newFolder().canonicalFile
            File(root, "source").writeText("original")
            File(root, "notes").mkdir()
            val fs = driftingFileSystem().apply { renameAcrossVolumes = { _, _ -> true } }
            fs.backend(root).move("/source", target)
            assertFalse(File(root, "source").exists())
            assertEquals("original", File(root, target.removePrefix("/")).readText())
        }
    }

    @Test fun `时间戳漂移不能掩盖同长度且还原mtime的并发写入`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val fs = driftingFileSystem()
        var changed = false
        fs.afterRead = { path ->
            if (path == source.toPath() && !changed) {
                changed = true
                val time = java.nio.file.Files.getLastModifiedTime(path)
                source.writeText("modified")
                java.nio.file.Files.setLastModifiedTime(path, time)
            }
        }
        val failure = runCatching { fs.backend(root).stat("/source") }.exceptionOrNull()
        assertEquals("CONFLICT", (failure as ExternalFolderException).code)
        assertTrue(failure.message!!, failure.message!!.contains("sha256"))
        assertEquals("modified", source.readText())
    }

    @Test fun `私有目录继续严格拒绝读后ctime变化`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "source").writeText("original")
        val fs = driftingFileSystem().apply { emulateFuse = false }
        val failure = runCatching { fs.backend(root).stat("/source") }.exceptionOrNull()
        assertEquals("CONFLICT", (failure as ExternalFolderException).code)
    }

    @Test fun `复制兜底目标校验失败时清理已创建但尚未返回回执的目标`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "source").writeText("original")
        val fs = JvmBoundRealPathFileSystem().apply {
            supportAtomicMoves = false
            renameAcrossVolumes = { _, _ -> true }
        }
        var failed = false
        fs.afterRead = { path ->
            if (path == File(root, "target").toPath() && !failed) {
                failed = true
                throw IOException("目标校验读取失败")
            }
        }
        assertNotNull(runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull())
        assertTrue(failed)
        assertEquals("original", File(root, "source").readText())
        assertFalse("失败后不得残留目标副本", File(root, "target").exists())
    }

    @Test fun `复制兜底源删除失败时仅保留源`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val fs = driftingFileSystem().apply { renameAcrossVolumes = { _, _ -> true } }
        fs.beforeOperation = { operation, path ->
            if (operation == "delete" && path == source.toPath()) throw IOException("源删除失败")
        }
        assertNotNull(runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull())
        assertEquals("original", source.readText())
        assertFalse(File(root, "target").exists())
    }

    @Test fun `复制兜底源已删除但返回错误时确认移动完成`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val fs = JvmBoundRealPathFileSystem().apply {
            supportAtomicMoves = false
            renameAcrossVolumes = { _, _ -> true }
        }
        fs.afterOperation = { operation, path ->
            if (operation == "delete" && path == source.toPath()) throw IOException("删除已提交但同步失败")
        }
        fs.backend(root).move("/source", "/target")
        assertFalse(source.exists())
        assertEquals("original", File(root, "target").readText())
    }

    @Test fun `复制兜底目录部分删除失败时补回缺失项再移除目标`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "source").mkdir()
        File(root, "source/a").writeText("first")
        File(root, "source/b").writeText("second")
        val fs = driftingFileSystem().apply { renameAcrossVolumes = { _, _ -> true } }
        fs.beforeOperation = { operation, path ->
            if (operation == "delete" && path == File(root, "source/b").toPath()) throw IOException("第二项删除失败")
        }
        assertNotNull(runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull())
        assertEquals("first", File(root, "source/a").readText())
        assertEquals("second", File(root, "source/b").readText())
        assertFalse(File(root, "target").exists())
    }

    @Test fun `复制兜底回滚遇并发目标修改时保留用户数据并报告两路径`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val fs = JvmBoundRealPathFileSystem().apply {
            supportAtomicMoves = false
            renameAcrossVolumes = { _, _ -> true }
        }
        fs.beforeOperation = { operation, path ->
            if (operation == "delete" && path == source.toPath()) {
                File(root, "target").writeText("user-content")
                throw IOException("源删除失败")
            }
        }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull() as ExternalFolderException
        assertEquals("RECOVERY_REQUIRED", failure.code)
        assertTrue(failure.message!!.contains("/source"))
        assertTrue(failure.message!!.contains("/target"))
        assertEquals("original", source.readText())
        assertEquals("user-content", File(root, "target").readText())
    }

    @Test fun `放宽移动明确跨卷时复制校验后删除来源`() = runBlocking {
        for (directory in listOf(false, true)) {
            val root = temporaryFolder.newFolder().canonicalFile
            val source = File(root, "source")
            if (directory) source.mkdir()
            val file = if (directory) File(source, "child") else source
            file.writeText("original")
            val fs = JvmBoundRealPathFileSystem().apply { supportAtomicMoves = false }
            fs.beforeOperation = { operation, path ->
                if (operation == "checked-move" && path == source.toPath()) throw ErrnoException("renameat", OsConstants.EXDEV)
            }
            fs.backend(root).move("/source", "/target")
            assertFalse(source.exists())
            assertEquals("original", (if (directory) File(root, "target/child") else File(root, "target")).readText())
        }
    }

    @Test fun `普通移动非跨卷错误不触发复制或删除`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val fs = JvmBoundRealPathFileSystem().apply { supportAtomicMoves = false }
        fs.beforeOperation = { operation, _ -> if (operation == "checked-move") throw IOException("移动失败") }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull()
        assertEquals("IO", (failure as ExternalFolderException).code)
        assertEquals("original", source.readText())
        assertFalse(File(root, "target").exists())
        assertTrue(fs.reads.isEmpty())
    }
}
