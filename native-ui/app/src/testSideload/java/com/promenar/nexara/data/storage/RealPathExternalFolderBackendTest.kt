package com.promenar.nexara.data.storage

import com.promenar.nexara.domain.storage.ExternalFolderBackend
import com.promenar.nexara.domain.storage.ExternalFolderException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RealPathExternalFolderBackendTest : ExternalFolderBackendContractTest() {
    @get:Rule val temporaryFolder = TemporaryFolder()

    override fun createBackend(): ExternalFolderBackend = JvmBoundRealPathFileSystem().backend(temporaryFolder.newFolder().canonicalFile)

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
        File(root, ".nexara-write-tmp").writeText("internal")
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
