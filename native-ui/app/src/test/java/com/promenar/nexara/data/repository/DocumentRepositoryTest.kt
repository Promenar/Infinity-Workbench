package com.promenar.nexara.data.repository

import app.cash.turbine.test
import com.promenar.nexara.data.local.db.dao.DocumentDao
import com.promenar.nexara.data.local.db.dao.FolderDao
import com.promenar.nexara.data.local.db.entity.DocumentEntity
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File

class DocumentRepositoryTest {

    private fun createEntity(
        id: String = "doc-1",
        title: String? = "Test",
        content: String? = "content",
        folderId: String? = "folder-1",
        fileSize: Long? = 100L,
        createdAt: Long = 1000L,
        updatedAt: Long? = 2000L,
        contentHash: String? = "hash"
    ) = DocumentEntity(
        id = id,
        title = title,
        content = content,
        folderId = folderId,
        fileSize = fileSize,
        createdAt = createdAt,
        updatedAt = updatedAt,
        contentHash = contentHash
    )

    @Test
    fun `observeByFolder returns documents from dao`() = runTest {
        val dao: DocumentDao = mockk()
        val folderDao: FolderDao = mockk()
        val repo = DocumentRepository(dao, folderDao)

        val entities = listOf(
            createEntity(id = "d1", folderId = "f1"),
            createEntity(id = "d2", folderId = "f1"),
            createEntity(id = "d3", folderId = "f2")
        )
        every { dao.observeAll() } returns flowOf(entities)

        repo.observeByFolder("f1").test {
            val docs = awaitItem()
            assertThat(docs).hasSize(2)
            assertThat(docs.all { it.folderId == "f1" }).isTrue()
            awaitComplete()
        }
    }

    @Test
    fun `observeByFolder returns empty when no match`() = runTest {
        val dao: DocumentDao = mockk()
        val folderDao: FolderDao = mockk()
        val repo = DocumentRepository(dao, folderDao)

        every { dao.observeAll() } returns flowOf(emptyList())

        repo.observeByFolder("nonexistent").test {
            assertThat(awaitItem()).isEmpty()
            awaitComplete()
        }
    }

    @Test
    fun `import creates entity and delegates to dao`() = runTest {
        val dao: DocumentDao = mockk(relaxed = true)
        val folderDao: FolderDao = mockk()
        val repo = DocumentRepository(dao, folderDao)

        val tempFile = File.createTempFile("test-doc", ".txt")
        tempFile.writeText("test content")
        try {
            val doc = repo.import(tempFile.absolutePath, "folder-1")

            assertThat(doc.title).isEqualTo(tempFile.name)
            assertThat(doc.content).isEqualTo("test content")
            assertThat(doc.folderId).isEqualTo("folder-1")
            assertThat(doc.hash).isNotEmpty()
            coVerify { dao.insert(any()) }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `import creates entity with SHA256 hash`() = runTest {
        val dao: DocumentDao = mockk(relaxed = true)
        val folderDao: FolderDao = mockk()
        val repo = DocumentRepository(dao, folderDao)

        val tempFile = File.createTempFile("hash-test", ".txt")
        tempFile.writeText("hello")
        try {
            val doc = repo.import(tempFile.absolutePath, "f1")

            val expectedHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest("hello".toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertThat(doc.hash).isEqualTo(expectedHash)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `update delegates to dao with updated content`() = runTest {
        val dao: DocumentDao = mockk(relaxed = true)
        val folderDao: FolderDao = mockk()
        val repo = DocumentRepository(dao, folderDao)

        val entity = createEntity(id = "doc-1", content = "old")
        coEvery { dao.getById("doc-1") } returns entity

        repo.update("doc-1", "new content")

        coVerify {
            dao.update(match { e ->
                e.id == "doc-1" && e.content == "new content" && e.contentHash != entity.contentHash
            })
        }
    }

    @Test
    fun `update does nothing when entity not found`() = runTest {
        val dao: DocumentDao = mockk(relaxed = true)
        val folderDao: FolderDao = mockk()
        val repo = DocumentRepository(dao, folderDao)

        coEvery { dao.getById("missing") } returns null

        repo.update("missing", "content")

        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun `delete delegates to dao`() = runTest {
        val dao: DocumentDao = mockk(relaxed = true)
        val folderDao: FolderDao = mockk()
        val repo = DocumentRepository(dao, folderDao)

        repo.delete("doc-1")

        coVerify { dao.deleteById("doc-1") }
    }

    @Test
    fun `markVectorized sets timestamp`() = runTest {
        val dao: DocumentDao = mockk(relaxed = true)
        val folderDao: FolderDao = mockk()
        val repo = DocumentRepository(dao, folderDao)

        repo.markVectorized("doc-1")

        coVerify { dao.updateVectorized("doc-1", 1) }
    }
}
