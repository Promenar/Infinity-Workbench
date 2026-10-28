package com.promenar.infinity.data.storage

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ReadOnlyExternalFolderBackend
import com.promenar.infinity.data.linux.LinuxFileSystemImpl
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadOnlyExternalFolderBackendTest {
    @Test fun `Linux主目录后端的全部修改原语也被外层只读装饰拦截`() = runBlocking {
        val files = mockk<LinuxFileSystemImpl>()
        every { files.forDirectory("/root", any()) } returns files
        val backend = ReadOnlyExternalFolderBackend(LinuxHomeExternalFolderBackend(files) { true })
        listOf<suspend () -> Any?>(
            { backend.write("/note.txt", byteArrayOf()) }, { backend.createDirectory("/new") },
            { backend.delete("/note.txt") }, { backend.move("/note.txt", "/new") }, { backend.copy("/note.txt", "/new") },
        ).forEach { action ->
            assertThat(assertThrows(ExternalFolderException::class.java) { runBlocking { action() } }.code).isEqualTo("READ_ONLY")
        }
        verify(exactly = 1) { files.forDirectory("/root", any()) }
        confirmVerified(files)
    }

    @Test fun `只读装饰器拒绝全部修改并保留查询`() = runBlocking {
        val memory = TransferMemoryBackend()
        memory.write("/note.txt", "资料".toByteArray())
        var delegatedWrites = 0
        val writable = object : ExternalFolderBackend by memory {
            override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean) =
                memory.write(path, content, expectedSha256, createOnly).also { delegatedWrites++ }
            override suspend fun createDirectory(path: String) = memory.createDirectory(path).also { delegatedWrites++ }
            override suspend fun delete(path: String, expectedSha256: String?) { delegatedWrites++; memory.delete(path, expectedSha256) }
            override suspend fun move(sourcePath: String, targetPath: String) { delegatedWrites++ }
            override suspend fun copy(sourcePath: String, targetPath: String) { delegatedWrites++ }
        }
        val backend = ReadOnlyExternalFolderBackend(writable)
        listOf<suspend () -> Any?>(
            { backend.write("/note.txt", byteArrayOf()) }, { backend.createDirectory("/new") },
            { backend.delete("/note.txt") }, { backend.move("/note.txt", "/new") }, { backend.copy("/note.txt", "/new") },
        ).forEach { action ->
            val failure = assertThrows(ExternalFolderException::class.java) { runBlocking { action() } }
            assertThat(failure.code).isEqualTo("READ_ONLY")
            assertThat(failure.message).isEqualTo("知识库挂载为只读")
        }
        assertThat(delegatedWrites).isEqualTo(0)
        assertThat(backend.read("/note.txt").toString(Charsets.UTF_8)).isEqualTo("资料")
        assertThat(backend.list("/").map { it.name }).containsExactly("note.txt")
        assertThat(backend.stat("/note.txt")?.size).isEqualTo("资料".toByteArray().size.toLong())
        backend.validateTree("/")
    }
}
