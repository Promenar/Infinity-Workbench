package com.promenar.nexara.data.storage

import android.system.Os
import com.promenar.nexara.domain.storage.ExternalFolderException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** 仅侧载测试 APK：直接执行 Android Os/procfs 句柄实现，不能用 JVM 替身代替此验收。 */
@RunWith(AndroidJUnit4::class)
class RealPathExternalFolderDeviceTest {
    @Test
    fun nativeDescriptorsDoNotAccumulateAcrossRepeatedIo() = runBlocking {
        val root = newDirectory("fd-count")
        try {
            val backend = RealPathExternalFolderBackend(root)
            repeat(4) { backend.write("/file.txt", "warm-$it".toByteArray()); backend.read("/file.txt") }
            val before = descriptorCount()
            repeat(64) { backend.write("/file.txt", "round-$it".toByteArray()); backend.read("/file.txt"); backend.stat("/file.txt") }
            assertTrue("重复读写后 fd 数应保持稳定", descriptorCount() <= before + 8)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun nativeLeafReplacementDuringCommitKeepsUserBytes() = runBlocking {
        for (operation in listOf("replace", "delete", "edit")) {
            val root = newDirectory("fd-leaf")
            val file = File(root, "file.txt").apply { writeText("inside") }
            var replaced = false
            val fileSystem = HookedAndroidFileSystem { current, path ->
                if (!replaced && current == (if (operation == "edit") "replace" else operation) && path == file.toPath()) {
                    replaced = true
                    if (operation != "edit") Os.rename(file.path, File(root, "saved.txt").path)
                    file.writeText("user-version")
                }
            }
            try {
                val backend = RealPathExternalFolderBackend(root, emptySet(), { true }, fileSystem)
                val expected = backend.stat("/file.txt")!!.sha256
                val failure = try {
                    if (operation == "delete") backend.delete("/file.txt", expected)
                    else backend.write("/file.txt", "tool-version".toByteArray(), expected)
                    null
                } catch (exception: ExternalFolderException) { exception }
                assertTrue(replaced)
                assertEquals("CONFLICT", failure?.code)
                assertEquals("user-version", file.readText())
            } finally { root.deleteRecursively() }
        }
    }

    @Test
    fun nativeFdBackendRoundTripsVersionsAndStatsUnreadableLargeFileWithoutOpeningBody() = runBlocking {
        val root = newDirectory("fd-basic")
        val large = File(root, "large.bin")
        try {
            val backend = RealPathExternalFolderBackend(root)
            val directory = backend.createDirectory("/notes")
            val original = backend.write("/notes/file.txt", "inside".toByteArray(), createOnly = true)
            assertEquals(original.identity, backend.stat("/notes/file.txt")!!.identity)
            assertArrayEquals("inside".toByteArray(), backend.read("/notes/file.txt"))
            assertEquals(listOf("file.txt"), backend.list("/notes").map { it.name })
            assertEquals(directory.identity, backend.stat("/notes")!!.identity)
            backend.write("/notes/file.txt", "updated".toByteArray(), original.sha256)
            large.writeBytes(ByteArray(1024 * 1024 + 1))
            Os.chmod(large.path, 0)
            assertNull(backend.stat("/large.bin")!!.sha256)
            val latest = backend.stat("/notes/file.txt")!!
            assertTrue(latest.identity != null)
            backend.delete("/notes/file.txt", latest.sha256)
            backend.delete("/notes")
        } finally {
            if (large.exists()) Os.chmod(large.path, 384)
            root.deleteRecursively()
        }
    }

    @Test
    fun nativeFdOperationsRemainBoundWhenParentBecomesSymbolicLink() = runBlocking {
        for (operation in listOf("read", "replace", "delete", "list")) {
            val root = newDirectory("fd-race")
            val outside = newDirectory("fd-outside")
            val notes = File(root, "notes").apply { check(mkdir()) }
            val moved = File(root, "moved")
            File(notes, "file.txt").writeText("inside")
            File(outside, "file.txt").writeText("outside")
            File(outside, "outside-only.txt").writeText("outside")
            var swapped = false
            val fileSystem = HookedAndroidFileSystem { current, path ->
                if (!swapped && current == operation && path.startsWith(notes.toPath())) {
                    swapped = true
                    Os.rename(notes.path, moved.path)
                    Os.symlink(outside.path, notes.path)
                }
            }
            try {
                val backend = RealPathExternalFolderBackend(root, emptySet(), { true }, fileSystem)
                when (operation) {
                    "read" -> assertArrayEquals("inside".toByteArray(), backend.read("/notes/file.txt"))
                    "replace" -> {
                        backend.write("/notes/file.txt", "updated".toByteArray())
                        assertEquals("updated", File(moved, "file.txt").readText())
                    }
                    "delete" -> {
                        backend.delete("/notes/file.txt")
                        assertFalse(File(moved, "file.txt").exists())
                    }
                    "list" -> assertEquals(listOf("file.txt"), backend.list("/notes").map { it.name })
                }
                assertTrue("测试必须实际交换父目录", swapped)
                assertEquals("outside", File(outside, "file.txt").readText())
                assertTrue(File(outside, "outside-only.txt").exists())
            } finally {
                if (Files.isSymbolicLink(notes.toPath())) Files.delete(notes.toPath())
                root.deleteRecursively()
                outside.deleteRecursively()
            }
        }
    }

    private fun newDirectory(prefix: String): File = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "$prefix-${UUID.randomUUID()}").canonicalFile.apply { check(mkdirs()) }

    private fun descriptorCount(): Int = File("/proc/self/fd").list()?.size ?: error("无法枚举进程 fd")

    /** 只在系统调用边界安排目录交换，所有读写与目录 fd 均委托真实 Android 实现。 */
    private class HookedAndroidFileSystem(private val before: (String, Path) -> Unit) : RealPathFileSystem {
        override fun openFileSystemRoot(): BoundRealPathDirectory = wrap(AndroidRealPathFileSystem.openFileSystemRoot(), File("/").toPath())
        private fun wrap(directory: BoundRealPathDirectory, path: Path): BoundRealPathDirectory = object : BoundRealPathDirectory by directory {
            override fun openDirectory(name: String): BoundRealPathDirectory = wrap(directory.openDirectory(name), path.resolve(name))
            override fun openRead(name: String): BoundRealPathRead {
                before("read", path.resolve(name))
                return directory.openRead(name)
            }
            override fun atomicReplace(source: String, target: String) {
                val operation = if (target.startsWith(".nexara-delete-")) "delete" else "replace"
                before(operation, path.resolve(if (source.startsWith(".nexara")) target else source))
                directory.atomicReplace(source, target)
            }
            override fun deleteFile(name: String) {
                before("delete", path.resolve(name))
                directory.deleteFile(name)
            }
            override fun names(): List<String> {
                before("list", path)
                return directory.names()
            }
        }
    }
}
