package com.promenar.infinity.data.storage

import android.app.Application
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.domain.storage.ExternalFolderException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
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
