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
