package com.promenar.nexara.data.storage

import kotlinx.coroutines.runBlocking
import com.promenar.nexara.domain.storage.ExternalFolderException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RealPathExternalFolderRaceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun parentReplacedBySymlinkAfterValidationNeverReadsOutsideMount() = runBlocking {
        val fixture = fixture()
        fixture.swapOn("read")
        val backend = fixture.fileSystem.backend(fixture.root)
        assertArrayEquals("inside".toByteArray(), backend.read("/notes/file.txt"))
        assertEquals("outside", File(fixture.outside, "file.txt").readText())
    }

    @Test
    fun parentReplacedBeforeAtomicRenameNeverOverwritesOutsideFile() = runBlocking {
        val fixture = fixture()
        fixture.swapOn("replace")
        fixture.fileSystem.backend(fixture.root).write("/notes/file.txt", "updated".toByteArray())
        assertEquals("updated", File(fixture.moved, "file.txt").readText())
        assertEquals("outside", File(fixture.outside, "file.txt").readText())
    }

    @Test
    fun parentReplacedBeforeUnlinkNeverDeletesOutsideFile() = runBlocking {
        val fixture = fixture()
        fixture.swapOn("delete")
        fixture.fileSystem.backend(fixture.root).delete("/notes/file.txt")
        assertFalse(File(fixture.moved, "file.txt").exists())
        assertEquals("outside", File(fixture.outside, "file.txt").readText())
    }

    @Test
    fun parentReplacedBeforeDirectoryIterationNeverListsOutsideChildren() = runBlocking {
        val fixture = fixture()
        File(fixture.outside, "outside-only.txt").writeText("outside")
        fixture.swapOn("list")
        assertEquals(listOf("file.txt"), fixture.fileSystem.backend(fixture.root).list("/notes").map { it.name })
    }

    @Test
    fun leafReplacementAfterVersionCheckIsPreservedDuringWrite() = runBlocking {
        val fixture = fixture()
        val file = File(fixture.original, "file.txt")
        var replaced = false
        fixture.fileSystem.beforeOperation = { operation, path ->
            if (operation == "replace" && path == file.toPath()) {
                fixture.fileSystem.beforeOperation = null
                replaced = true
                Files.move(file.toPath(), File(fixture.root, "saved.txt").toPath())
                file.writeText("user-version")
            }
        }
        val failure = try { fixture.fileSystem.backend(fixture.root).write("/notes/file.txt", "tool-version".toByteArray()); null }
        catch (exception: ExternalFolderException) { exception }
        assertTrue(replaced)
        assertEquals("CONFLICT", failure?.code)
        assertEquals("user-version", file.readText())
    }

    @Test
    fun leafReplacementAfterVersionCheckIsPreservedDuringDelete() = runBlocking {
        val fixture = fixture()
        val file = File(fixture.original, "file.txt")
        var replaced = false
        fixture.fileSystem.beforeOperation = { operation, path ->
            if (operation == "delete" && path == file.toPath()) {
                fixture.fileSystem.beforeOperation = null
                replaced = true
                Files.move(file.toPath(), File(fixture.root, "saved.txt").toPath())
                file.writeText("user-version")
            }
        }
        val failure = try { fixture.fileSystem.backend(fixture.root).delete("/notes/file.txt"); null }
        catch (exception: ExternalFolderException) { exception }
        assertTrue(replaced)
        assertEquals("CONFLICT", failure?.code)
        assertEquals("user-version", file.readText())
    }

    @Test
    fun inPlaceEditAfterVersionCheckIsPreservedDuringWrite() = runBlocking {
        val fixture = fixture()
        val file = File(fixture.original, "file.txt")
        fixture.fileSystem.beforeOperation = { operation, path ->
            if (operation == "replace" && path == file.toPath()) {
                fixture.fileSystem.beforeOperation = null
                file.writeText("user-version")
            }
        }
        val failure = try { fixture.fileSystem.backend(fixture.root).write("/notes/file.txt", "tool-version".toByteArray()); null }
        catch (exception: ExternalFolderException) { exception }
        assertEquals("CONFLICT", failure?.code)
        assertEquals("user-version", file.readText())
    }

    @Test
    fun occupiedNameDuringPublishRetainsBothVersionsAndReportsQuarantine() = runBlocking {
        val fixture = fixture()
        val file = File(fixture.original, "file.txt")
        fixture.publishingTo(file) { file.writeText("concurrent") }
        val failure = try { fixture.fileSystem.backend(fixture.root).write("/notes/file.txt", "tool-version".toByteArray()); null }
        catch (exception: ExternalFolderException) { exception }
        assertEquals("RECOVERY_REQUIRED", failure?.code)
        assertEquals("concurrent", file.readText())
        // 原文隔离副本留在根下内部目录，不散落到用户目录。
        assertFalse(fixture.original.listFiles()!!.any { it.name.startsWith(".nexara") })
        val retained = File(fixture.root, ".nexara").listFiles()!!.single { it.name.startsWith(".nexara-replace-") }
        assertEquals("inside", retained.readText())
        assertTrue(failure!!.message.contains(retained.name))
    }

    @Test
    fun failedQuarantineDeletionRestoresFileWithoutReplacingAnotherObject() = runBlocking {
        val fixture = fixture()
        fixture.fileSystem.beforeOperation = { operation, path ->
            if (operation == "delete" && path.fileName.toString().startsWith(".nexara-delete-")) {
                fixture.fileSystem.beforeOperation = null
                throw java.io.IOException("测试隔离删除失败")
            }
        }
        val failure = try { fixture.fileSystem.backend(fixture.root).delete("/notes/file.txt"); null }
        catch (exception: ExternalFolderException) { exception }
        assertEquals("UNAVAILABLE", failure?.code)
        assertEquals("inside", File(fixture.original, "file.txt").readText())
        assertEquals(listOf("file.txt"), fixture.original.list()!!.toList())
    }

    @Test
    fun newFileDoesNotOverwriteConcurrentCreator() = runBlocking {
        val fixture = fixture()
        val file = File(fixture.original, "new.txt")
        fixture.publishingTo(file) { file.writeText("concurrent") }
        val failure = try { fixture.fileSystem.backend(fixture.root).write("/notes/new.txt", "tool-version".toByteArray(), createOnly = true); null }
        catch (exception: ExternalFolderException) { exception }
        assertEquals("CONFLICT", failure?.code)
        assertEquals("concurrent", file.readText())
    }

    private fun fixture(): Fixture {
        val root = temporaryFolder.newFolder().canonicalFile
        val original = File(root, "notes").apply { mkdirs() }
        val outside = temporaryFolder.newFolder().canonicalFile
        File(original, "file.txt").writeText("inside")
        File(outside, "file.txt").writeText("outside")
        return Fixture(root, original, File(root, "moved"), outside, JvmBoundRealPathFileSystem())
    }

    private data class Fixture(val root: File, val original: File, val moved: File, val outside: File, val fileSystem: JvmBoundRealPathFileSystem) {
        /** 新内容在根下 `.nexara/` 暂存，发布到 [target] 前一刻触发 [concurrent]。 */
        fun publishingTo(target: File, concurrent: () -> Unit) {
            val internal = File(root, ".nexara").toPath()
            fileSystem.beforeOperation = { operation, path ->
                if (operation == "move" && path.parent == internal && path.fileName.toString().startsWith(".nexara-write-") && target.parentFile.exists()) {
                    fileSystem.beforeOperation = null
                    concurrent()
                }
            }
        }

        fun swapOn(operation: String) {
            fileSystem.beforeOperation = { current, path ->
                if (current == operation && path.startsWith(original.toPath())) {
                    fileSystem.beforeOperation = null
                    Files.move(original.toPath(), moved.toPath())
                    Files.createSymbolicLink(original.toPath(), outside.toPath())
                    fileSystem.movedDirectories[original.toPath()] = moved.toPath()
                }
            }
        }
    }
}
