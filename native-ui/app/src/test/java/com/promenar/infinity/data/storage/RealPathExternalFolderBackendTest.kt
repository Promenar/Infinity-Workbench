package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class RealPathExternalFolderBackendTest : ExternalFolderBackendContractTest() {
    @get:Rule val temporaryFolder = TemporaryFolder()

    override fun createBackend(): ExternalFolderBackend = JvmBoundRealPathFileSystem().backend(temporaryFolder.newFolder().canonicalFile)

    @Test fun `写入删除复制的暂存与隔离仅位于根隐藏目录`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "notes").mkdir()
        val fs = JvmBoundRealPathFileSystem()
        val internalOperations = mutableListOf<java.nio.file.Path>()
        fs.beforeOperation = { _, path ->
            if (path.fileName.toString().startsWith(".infinity-")) internalOperations.add(path)
        }
        val backend = fs.backend(root)
        val first = backend.write("/notes/file.txt", "original".toByteArray())
        backend.write("/notes/file.txt", "changed".toByteArray(), first.sha256)
        backend.copy("/notes/file.txt", "/notes/copied.txt")
        backend.delete("/notes/file.txt")
        assertTrue(internalOperations.isNotEmpty())
        assertTrue(internalOperations.all { it.startsWith(File(root, ".infinity").toPath()) })
        assertEquals("*\n", File(root, ".infinity/.gitignore").readText())
        assertEquals(listOf("copied.txt"), File(root, "notes").list()!!.toList())
        assertEquals(listOf(".gitignore"), File(root, ".infinity").list()!!.toList())
    }

    @Test fun `流式读取逐块交出内容并按上限拒绝`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val content = ByteArray(64 * 1024) { (it % 251).toByte() }
        File(root, "big.bin").writeBytes(content)
        val backend = JvmBoundRealPathFileSystem().backend(root)
        assertArrayEquals(content, backend.readStream("/big.bin", content.size.toLong()) { it.readBytes() })
        val tooLarge = runCatching { backend.readStream("/big.bin", content.size - 1L) { it.readBytes() } }.exceptionOrNull()
        assertEquals("TOO_LARGE", (tooLarge as ExternalFolderException).code)
        val directory = runCatching { backend.readStream("/", 1L) { it.read() } }.exceptionOrNull()
        assertEquals("IS_DIRECTORY", (directory as ExternalFolderException).code)
    }

    @Test fun `根隐藏目录的忽略规则仅在创建时写入`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val backend = JvmBoundRealPathFileSystem().backend(root)
        backend.write("/a", "a".toByteArray())
        val ignore = File(root, ".infinity/.gitignore")
        assertEquals("*\n", ignore.readText())
        ignore.writeText("*.tmp\n")
        backend.write("/b", "b".toByteArray())
        assertEquals("*.tmp\n", ignore.readText())
        ignore.delete()
        JvmBoundRealPathFileSystem().backend(root).write("/c", "c".toByteArray())
        assertTrue(!ignore.exists())
    }

    @Test fun `拒绝将根隐藏目录链接至挂载外部`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val outside = temporaryFolder.newFolder().canonicalFile
        Files.createSymbolicLink(File(root, ".infinity").toPath(), outside.toPath())
        val backend = JvmBoundRealPathFileSystem().backend(root)
        expectCode("INVALID_PATH") { backend.write("/new", "a".toByteArray()) }
        assertTrue(outside.list()!!.isEmpty())
        assertTrue(!File(root, "new").exists())
    }

    @Test fun `同卷发布先原子移动且不在用户目标创建半成品`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val fs = JvmBoundRealPathFileSystem()
        fs.beforeOperation = { operation, path ->
            if (operation == "create" && path == File(root, "file").toPath()) throw IOException("用户目标不得先创建半成品")
        }
        fs.backend(root).write("/file", "complete".toByteArray())
        assertEquals("complete", File(root, "file").readText())
    }

    @Test fun `首次访问将已知旧暂存节点保留至根隐藏目录并保持原相对位置`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val name = ".infinity-write-11111111-2222-4333-8444-555555555555"
        val nested = ".infinity-copy-cleanup-66666666-7777-4888-8999-aaaaaaaaaaaa"
        File(root, name).writeText("root-recovery")
        File(root, "notes/$nested").mkdirs()
        File(root, "notes/$nested/data").writeText("nested-recovery")
        File(root, ".infinity-unknown-11111111-2222-4333-8444-555555555555").writeText("user")
        val backend = JvmBoundRealPathFileSystem().backend(root)
        backend.list("/")
        backend.list("/notes")
        assertEquals("root-recovery", File(root, ".infinity/legacy/$name").readText())
        assertEquals("nested-recovery", File(root, ".infinity/legacy/notes/$nested/data").readText())
        assertTrue(!File(root, name).exists())
        assertTrue(!File(root, "notes/$nested").exists())
        assertEquals("user", File(root, ".infinity-unknown-11111111-2222-4333-8444-555555555555").readText())
        JvmBoundRealPathFileSystem().backend(root).list("/notes")
        assertEquals("nested-recovery", File(root, ".infinity/legacy/notes/$nested/data").readText())
    }

    @Test fun `更名前暂存节点迁入当前隐藏目录并保留未知文件`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val name = ".nexara-write-11111111-2222-4333-8444-555555555555"
        val nested = ".nexara-copy-cleanup-66666666-7777-4888-8999-aaaaaaaaaaaa"
        File(root, name).writeText("root-recovery")
        File(root, "notes/$nested").mkdirs()
        File(root, "notes/$nested/data").writeText("nested-recovery")
        File(root, ".nexara-unknown-11111111-2222-4333-8444-555555555555").writeText("user")
        val backend = JvmBoundRealPathFileSystem().backend(root)
        backend.list("/")
        backend.list("/notes")
        assertEquals("root-recovery", File(root, ".infinity/legacy/$name").readText())
        assertEquals("nested-recovery", File(root, ".infinity/legacy/notes/$nested/data").readText())
        assertTrue(!File(root, name).exists())
        assertTrue(!File(root, "notes/$nested").exists())
        assertEquals("user", File(root, ".nexara-unknown-11111111-2222-4333-8444-555555555555").readText())
        JvmBoundRealPathFileSystem().backend(root).list("/notes")
        assertEquals("nested-recovery", File(root, ".infinity/legacy/notes/$nested/data").readText())
    }

    @Test fun `旧暂存迁移冲突或失败不覆盖不删除且可重试`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val name = ".infinity-replace-11111111-2222-4333-8444-555555555555"
        val source = File(root, "notes/$name").apply { parentFile!!.mkdirs(); writeText("source") }
        val target = File(root, ".infinity/legacy/notes/$name").apply { parentFile!!.mkdirs(); writeText("preserved") }
        val fs = JvmBoundRealPathFileSystem()
        fs.backend(root).list("/notes")
        assertEquals("source", source.readText())
        assertEquals("preserved", target.readText())
        target.delete()
        var migrationRejected = false
        fs.beforeOperation = { operation, path ->
            if (operation == "replace" && path == source.toPath()) {
                migrationRejected = true
                throw IOException("迁移暂不可用")
            }
        }
        fs.backend(root).list("/notes")
        assertTrue(migrationRejected)
        assertEquals("source", source.readText())
        fs.beforeOperation = null
        fs.backend(root).list("/notes")
        assertTrue(!source.exists())
        assertEquals("source", target.readText())
    }

    @Test fun `发布已移动后报错只清理未修改的本次目标且保留并发替换`() = runBlocking {
        for (replace in listOf(false, true)) {
            val root = temporaryFolder.newFolder().canonicalFile
            var fail = true
            val base = JvmBoundRealPathFileSystem()
            fun unwrap(directory: BoundRealPathDirectory) = (directory as? PublishDirectory)?.delegate ?: directory
            fun wrap(directory: BoundRealPathDirectory): BoundRealPathDirectory = PublishDirectory(directory, ::wrap) { source, target, name ->
                directory.moveNew(source, unwrap(target), name)
                if (source.startsWith(".infinity-write-") && fail) {
                    fail = false
                    if (replace) {
                        Files.move(File(root, name).toPath(), File(root, "saved").toPath())
                        File(root, name).writeText("concurrent")
                    }
                    throw IOException("发布后同步失败")
                }
            }
            val fs = object : RealPathFileSystem { override fun openFileSystemRoot() = wrap(base.openFileSystemRoot()) }
            expectCode("UNAVAILABLE") { RealPathExternalFolderBackend(root, emptySet(), { true }, fs).write("/file", "complete".toByteArray()) }
            if (replace) assertEquals("concurrent", File(root, "file").readText())
            else assertTrue(!File(root, "file").exists())
        }
    }

    @Test fun `发布失败后补偿移动已提交但同步报错仍保留唯一原文`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "file").writeText("original")
        var publishFailed = false
        var recoveryCommitted = false
        val base = JvmBoundRealPathFileSystem()
        fun unwrap(directory: BoundRealPathDirectory) = (directory as? PublishDirectory)?.delegate ?: directory
        fun wrap(directory: BoundRealPathDirectory): BoundRealPathDirectory = PublishDirectory(directory, ::wrap) { source, target, name ->
            if (source.startsWith(".infinity-write-") && !publishFailed) {
                publishFailed = true
                throw IOException("发布前失败")
            }
            directory.moveNew(source, unwrap(target), name)
            if (source.startsWith(".infinity-replace-")) {
                recoveryCommitted = true
                throw IOException("补偿移动已提交但同步失败")
            }
        }
        val fs = object : RealPathFileSystem { override fun openFileSystemRoot() = wrap(base.openFileSystemRoot()) }
        val backend = RealPathExternalFolderBackend(root, emptySet(), { true }, fs)
        val original = backend.stat("/file")!!
        expectCode("UNAVAILABLE") { backend.write("/file", "changed".toByteArray(), original.sha256) }
        assertTrue(publishFailed)
        assertTrue(recoveryCommitted)
        assertEquals("original", File(root, "file").readText())
        assertEquals(listOf(".gitignore"), File(root, ".infinity").list()!!.toList())
    }

    private class PublishDirectory(
        val delegate: BoundRealPathDirectory,
        private val wrap: (BoundRealPathDirectory) -> BoundRealPathDirectory,
        private val move: (String, BoundRealPathDirectory, String) -> Unit,
    ) : BoundRealPathDirectory by delegate {
        override fun openDirectory(name: String) = wrap(delegate.openDirectory(name))
        override fun moveNew(source: String, targetDirectory: BoundRealPathDirectory, target: String) = move(source, targetDirectory, target)
    }

    @Test fun `目标祖先目录实际绑定到来源子目录时拒绝复制与移动`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "source/nested").mkdirs()
        File(root, "source/a").writeText("来源")
        File(root, "alias").mkdir()
        val fs = JvmBoundRealPathFileSystem()
        fs.movedDirectories[File(root, "alias").toPath()] = File(root, "source").toPath()
        val backend = fs.backend(root)
        expectCode("TARGET_INSIDE_SOURCE") { backend.copy("/source", "/alias/nested/copy") }
        expectCode("TARGET_INSIDE_SOURCE") { backend.move("/source", "/alias/nested/moved") }
        assertEquals("来源", File(root, "source/a").readText())
        assertEquals(emptyList<String>(), File(root, "source/nested").list()!!.toList())
    }

    @Test
    fun rejectsSymbolicLinksForReadWriteListDeleteAndRoot() = runBlocking {
        val root = temporaryFolder.newFolder("root").canonicalFile
        val outside = temporaryFolder.newFolder("outside").canonicalFile
        File(outside, "secret.txt").writeText("outside")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        val backend = JvmBoundRealPathFileSystem().backend(root)
        expectCode("INVALID_PATH") { backend.read("/link/secret.txt") }
        expectCode("INVALID_PATH") { backend.write("/link/new.txt", "wrong".toByteArray()) }
        expectCode("INVALID_PATH") { backend.list("/link") }
        expectCode("INVALID_PATH") { backend.delete("/link") }
        assertArrayEquals("outside".toByteArray(), File(outside, "secret.txt").readBytes())
        val rootLink = File(root, "root-link")
        Files.createSymbolicLink(rootLink.toPath(), outside.toPath())
        expectCode("INVALID_PATH") { JvmBoundRealPathFileSystem().backend(rootLink).stat("/") }
    }

    @Test
    fun directoryListingDoesNotExposeInternalTemporaryFiles() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, ".infinity-write-tmp").writeText("internal")
        File(root, ".recycle_bin").mkdir()
        File(root, "visible.txt").writeText("visible")
        assertEquals(listOf("visible.txt"), JvmBoundRealPathFileSystem().backend(root).list("/").map { it.name })
    }

    @Test
    fun volumeMountCannotEnterProtectedAndroidTreeThroughCaseVariants() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "aNdRoId/data/other").mkdirs()
        File(root, "notes").mkdirs()
        val backend = JvmBoundRealPathFileSystem().backend(root, setOf(File(root, "Android").toPath()))
        expectCode("INVALID_PATH") { backend.stat("/aNdRoId/data") }
        expectCode("INVALID_PATH") { backend.write("/aNdRoId/data/new.txt", byteArrayOf()) }
        expectCode("INVALID_PATH") { backend.delete("/aNdRoId") }
        assertEquals(listOf("notes"), backend.list("/").map { it.name })
    }

    @Test
    fun existingBackendRechecksPermissionForEveryOperation() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        var allowed = true
        val backend = JvmBoundRealPathFileSystem().backend(root, permissionCheck = { allowed })
        backend.write("/file.txt", "original".toByteArray())
        allowed = false
        expectCode("UNAVAILABLE") { backend.read("/file.txt") }
        expectCode("UNAVAILABLE") { backend.write("/file.txt", byteArrayOf()) }
        assertArrayEquals("original".toByteArray(), File(root, "file.txt").readBytes())
    }

    @Test
    fun largeFileStatDoesNotOpenContentAndDirectoryIdentitySurvivesChildMutations() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "large.bin").writeBytes(ByteArray(1024 * 1024 + 1))
        val fileSystem = JvmBoundRealPathFileSystem()
        val backend = fileSystem.backend(root)
        assertNull(backend.stat("/large.bin")!!.sha256)
        assertEquals(emptyList<java.nio.file.Path>(), fileSystem.reads)
        val directory = backend.createDirectory("/notes")
        backend.write("/notes/a.txt", "a".toByteArray())
        assertEquals(directory.identity, backend.stat("/notes")!!.identity)
        backend.delete("/notes/a.txt")
        assertEquals(directory.identity, backend.stat("/notes")!!.identity)
    }

    @Test
    fun hardLinkPublicationReturnsIdentityAfterTemporaryLinkCleanup() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val fileSystem = JvmBoundRealPathFileSystem().apply { supportHardLinks = true }
        val backend = fileSystem.backend(root)
        val created = backend.write("/file.txt", "inside".toByteArray(), createOnly = true)
        assertEquals(created.identity, backend.stat("/file.txt")!!.identity)
    }

    @Test
    fun disappearedMountRootIsUnavailableInsteadOfAnAbsentUserFile() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val backend = JvmBoundRealPathFileSystem().backend(root)
        root.delete()
        expectCode("UNAVAILABLE") { backend.stat("/") }
    }
}
