package com.promenar.nexara.data.storage

import com.promenar.nexara.domain.storage.ExternalFolderBackend
import com.promenar.nexara.domain.storage.ExternalFolderEntry
import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.domain.storage.ExternalFolderPaths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** 相同用例同时约束内存、SAF 和真实目录的可观察行为。 */
abstract class ExternalFolderBackendContractTest {
    abstract fun createBackend(): ExternalFolderBackend

    @Test
    fun rootExistsAndMissingEntriesRemainAbsent() = runBlocking {
        val backend = createBackend()
        assertTrue(backend.stat("/")!!.isDirectory)
        assertNull(backend.stat("/missing"))
        assertEquals(emptyList<ExternalFolderEntry>(), backend.list("/"))
        expectCode("NOT_FOUND") { backend.read("/missing") }
        expectCode("NOT_FOUND") { backend.list("/missing") }
    }

    @Test
    fun readsListsVersionsAndDeletesUtf8Content() = runBlocking {
        val backend = createBackend()
        val directory = backend.createDirectory("/notes")
        assertEquals("/notes", directory.path)
        assertTrue(directory.isDirectory)
        val created = backend.write("/notes/hello.txt", "hello".toByteArray(), createOnly = true)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", created.sha256)
        assertEquals(5L, created.size)
        assertFalse(created.isDirectory)
        assertArrayEquals("hello".toByteArray(), backend.read("/notes/hello.txt"))
        assertEquals(listOf("hello.txt"), backend.list("/notes").map { it.name })
        assertEquals(created.sha256, backend.stat("/notes/hello.txt")!!.sha256)
        val updated = backend.write("/notes/hello.txt", "你好".toByteArray(), created.sha256)
        assertNotNull(updated.sha256)
        assertArrayEquals("你好".toByteArray(), backend.read("/notes/hello.txt"))
        backend.delete("/notes/hello.txt", updated.sha256)
        backend.delete("/notes")
        assertNull(backend.stat("/notes"))
    }

    @Test
    fun conflictingVersionAndCreateOnlyNeverOverwriteExistingBytes() = runBlocking {
        val backend = createBackend()
        val original = backend.write("/file.txt", "original".toByteArray())
        expectCode("CONFLICT") { backend.write("/file.txt", "changed".toByteArray(), "0".repeat(64)) }
        expectCode("CONFLICT") { backend.write("/file.txt", byteArrayOf(), createOnly = true) }
        expectCode("CONFLICT") { backend.delete("/file.txt", "0".repeat(64)) }
        expectCode("CONFLICT") { backend.write("/absent.txt", byteArrayOf(), original.sha256) }
        assertArrayEquals("original".toByteArray(), backend.read("/file.txt"))
        assertNull(backend.stat("/absent.txt"))
    }

    @Test
    fun readBudgetRejectsActualBytesAndDirectoryOperationsAreTyped() = runBlocking {
        val backend = createBackend()
        backend.write("/file.txt", "12345".toByteArray())
        backend.createDirectory("/folder")
        expectCode("TOO_LARGE") { backend.read("/file.txt", 4) }
        assertArrayEquals("12345".toByteArray(), backend.read("/file.txt", 5))
        expectCode("CONFLICT") { backend.createDirectory("/file.txt") }
        expectCode("CONFLICT") { backend.write("/folder", byteArrayOf()) }
        expectCode("NOT_DIRECTORY") { backend.list("/file.txt") }
        expectCode("IS_DIRECTORY") { backend.read("/folder") }
    }

    @Test
    fun traversalReservedNamesAndRootMutationAreRejected() = runBlocking {
        val backend = createBackend()
        listOf("/../escape", "/./file", "/a\\b", "/a\u0000b", "/.nexara-cache/a", "/a/.recycle_bin/b").forEach { path ->
            expectCode("INVALID_PATH") { backend.stat(path) }
            expectCode("INVALID_PATH") { backend.write(path, byteArrayOf()) }
        }
        expectCode("INVALID_PATH") { backend.delete("/") }
        expectCode("INVALID_PATH") { backend.write("/", byteArrayOf()) }
    }

    protected suspend fun expectCode(code: String, action: suspend () -> Any?) {
        val failure = try {
            action()
            null
        } catch (exception: ExternalFolderException) {
            exception
        }
        assertEquals("操作应拒绝并给出明确错误码", code, failure?.code)
    }
}

class InMemoryExternalFolderBackendContractTest : ExternalFolderBackendContractTest() {
    override fun createBackend(): ExternalFolderBackend = MemoryExternalFolderBackend()
}

/** 契约替身只位于测试源集，不参与生产路由。 */
private class MemoryExternalFolderBackend : ExternalFolderBackend {
    private val entries = linkedMapOf("/" to ExternalFolderEntry("/", "root", true, 0, 0))
    private val contents = mutableMapOf<String, ByteArray>()

    override suspend fun stat(path: String): ExternalFolderEntry? = entries[ExternalFolderPaths.normalize(path)]

    override suspend fun list(path: String): List<ExternalFolderEntry> {
        val normalized = ExternalFolderPaths.normalize(path)
        val entry = entries[normalized] ?: fail("NOT_FOUND")
        if (!entry.isDirectory) fail("NOT_DIRECTORY")
        return entries.values.filter { it.path != "/" && it.path.substringBeforeLast('/').ifEmpty { "/" } == normalized }
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray {
        val normalized = ExternalFolderPaths.normalize(path)
        val entry = entries[normalized] ?: fail("NOT_FOUND")
        if (entry.isDirectory) fail("IS_DIRECTORY")
        val bytes = contents.getValue(normalized)
        if (bytes.size > maxBytes) fail("TOO_LARGE")
        return bytes.copyOf()
    }

    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry {
        val normalized = mutationPath(path)
        val existing = entries[normalized]
        if (existing?.isDirectory == true || (createOnly && existing != null)) fail("CONFLICT")
        if (expectedSha256 != null && expectedSha256 != existing?.sha256) fail("CONFLICT")
        requireParent(normalized)
        val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        return ExternalFolderEntry(normalized, normalized.substringAfterLast('/'), false, content.size.toLong(), 1, hash).also {
            contents[normalized] = content.copyOf()
            entries[normalized] = it
        }
    }

    override suspend fun createDirectory(path: String): ExternalFolderEntry {
        val normalized = mutationPath(path)
        if (entries.containsKey(normalized)) fail("CONFLICT")
        requireParent(normalized)
        return ExternalFolderEntry(normalized, normalized.substringAfterLast('/'), true, 0, 1).also { entries[normalized] = it }
    }

    override suspend fun delete(path: String, expectedSha256: String?) {
        val normalized = mutationPath(path)
        val existing = entries[normalized] ?: fail("NOT_FOUND")
        if (expectedSha256 != null && expectedSha256 != existing.sha256) fail("CONFLICT")
        entries.keys.filter { it == normalized || it.startsWith("$normalized/") }.toList().forEach {
            entries.remove(it)
            contents.remove(it)
        }
    }

    private fun mutationPath(path: String): String = ExternalFolderPaths.normalize(path).also { if (it == "/") fail("INVALID_PATH") }
    private fun requireParent(path: String) {
        val parent = entries[path.substringBeforeLast('/').ifEmpty { "/" }] ?: fail("NOT_FOUND")
        if (!parent.isDirectory) fail("NOT_DIRECTORY")
    }
    private fun fail(code: String): Nothing = throw ExternalFolderException(code, "测试替身拒绝操作：$code")
}
