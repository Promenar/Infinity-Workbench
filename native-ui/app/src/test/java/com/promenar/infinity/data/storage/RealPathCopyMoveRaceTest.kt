package com.promenar.infinity.data.storage

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.domain.storage.ExternalFolderException
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class RealPathCopyMoveRaceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fileSystem() = JvmBoundRealPathFileSystem().apply {
        emulateFuse = true
        supportAtomicMoves = false
        renameAcrossVolumes = { _, _ -> true }
        readStateAfterRead = { _, state -> state.copy(revision = "${state.revision}:ctime-drift") }
    }

    @Test fun `EXDEV返回时同名来源已被替换则拒绝移动替换者`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val fs = fileSystem()
        fs.renameAcrossVolumes = { from, _ ->
            if (from == source.toPath()) {
                Files.move(from, File(root, "saved").toPath())
                source.writeText("replaced")
            }
            true
        }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull()
        assertEquals("CONFLICT", (failure as ExternalFolderException).code)
        assertEquals("replaced", source.readText())
        assertEquals("original", File(root, "saved").readText())
        assertFalse(File(root, "target").exists())
    }

    @Test fun `源复核期间目标消失或修改时保全原始内容`() = runBlocking {
        for (removeTarget in listOf(true, false)) {
            val root = temporary.newFolder().canonicalFile
            val source = File(root, "source").apply { writeText("original") }
            val target = File(root, "target")
            val fs = fileSystem()
            var changed = false
            fs.afterRead = { path ->
                if (!changed && path == source.toPath() && target.exists()) {
                    changed = true
                    if (removeTarget) target.delete() else target.writeText("external")
                }
            }
            val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(changed)
            assertEquals("original", source.readText())
            if (removeTarget) assertFalse(target.exists()) else {
                assertEquals("RECOVERY_REQUIRED", (failure as ExternalFolderException).code)
                assertEquals("external", target.readText())
            }
        }
    }

    @Test fun `源删除已提交后目标被移除则从快照恢复来源`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val target = File(root, "target")
        val fs = fileSystem()
        fs.afterOperation = { operation, path -> if (operation == "delete" && path == source.toPath()) target.delete() }
        assertNotNull(runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull())
        assertEquals("original", source.readText())
        assertFalse(target.exists())
    }

    @Test fun `独立摘要读取结束时并发修改不能借旧摘要通过`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val fs = fileSystem()
        var reads = 0
        fs.afterRead = { path ->
            if (path == source.toPath() && ++reads == 2) source.writeText("modified")
        }
        val failure = runCatching { fs.backend(root).stat("/source") }.exceptionOrNull()
        assertEquals("CONFLICT", (failure as ExternalFolderException).code)
        assertEquals("modified", source.readText())
    }

    @Test fun `回滚不得把并发截短的完整目标当作未写完副本删除`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val target = File(root, "target")
        val fs = fileSystem()
        fs.beforeOperation = { operation, path ->
            if (operation == "delete" && path == source.toPath()) {
                target.writeText("orig")
                throw java.io.IOException("源删除失败")
            }
        }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull()
        assertEquals("RECOVERY_REQUIRED", (failure as ExternalFolderException).code)
        assertEquals("original", source.readText())
        assertEquals("orig", target.readText())
    }

    @Test fun `删除前来源被外部改名时不得谎报移动成功`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val fs = JvmBoundRealPathFileSystem().apply {
            emulateFuse = true
            supportAtomicMoves = false
            renameAcrossVolumes = { _, _ -> true }
        }
        var sourceReads = 0
        fs.beforeOperation = { operation, path ->
            if (operation == "read" && path == source.toPath() && ++sourceReads == 3) {
                Files.move(path, File(root, "saved").toPath())
            }
        }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull()
        assertEquals("RECOVERY_REQUIRED", (failure as ExternalFolderException).code)
        assertEquals("original", File(root, "saved").readText())
        assertEquals("original", File(root, "target").readText())
    }

    @Test fun `目标复核期间来源被同inode改写时保留新内容`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val fs = JvmBoundRealPathFileSystem().apply {
            emulateFuse = true
            supportAtomicMoves = false
            renameAcrossVolumes = { _, _ -> true }
        }
        var targetReads = 0
        fs.afterRead = { path ->
            if (path == File(root, "target").toPath() && ++targetReads == 3) source.writeText("modified")
        }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("modified", source.readText())
        assertFalse(File(root, "target").exists())
    }
}
