package com.promenar.nexara.data.mapper

import com.promenar.nexara.data.local.db.entity.DocumentEntity
import com.promenar.nexara.domain.model.Document
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DocumentMapperTest {

    private fun createEntity(
        id: String = "doc-1",
        title: String? = "Test Document",
        content: String? = "Hello world",
        source: String? = null,
        type: String = "text",
        folderId: String? = "folder-1",
        vectorized: Int = 0,
        vectorCount: Int = 0,
        fileSize: Long? = 1024,
        createdAt: Long = 1000L,
        updatedAt: Long? = 2000L,
        metadata: String? = null,
        isGlobal: Int = 0,
        contentHash: String? = "abc123"
    ) = DocumentEntity(
        id = id,
        title = title,
        content = content,
        source = source,
        type = type,
        folderId = folderId,
        vectorized = vectorized,
        vectorCount = vectorCount,
        fileSize = fileSize,
        createdAt = createdAt,
        updatedAt = updatedAt,
        metadata = metadata,
        isGlobal = isGlobal,
        contentHash = contentHash
    )

    @Test
    fun `toDomain maps all fields`() {
        val entity = createEntity()
        val doc = DocumentMapper.toDomain(entity)

        assertThat(doc.id).isEqualTo("doc-1")
        assertThat(doc.folderId).isEqualTo("folder-1")
        assertThat(doc.title).isEqualTo("Test Document")
        assertThat(doc.content).isEqualTo("Hello world")
        assertThat(doc.hash).isEqualTo("abc123")
        assertThat(doc.createdAt).isEqualTo(1000L)
        assertThat(doc.updatedAt).isEqualTo(2000L)
    }

    @Test
    fun `toDomain maps vectorized flag to vectorizedAt`() {
        val entity = createEntity(vectorized = 1, updatedAt = 5000L)
        val doc = DocumentMapper.toDomain(entity)
        assertThat(doc.vectorizedAt).isEqualTo(5000L)
    }

    @Test
    fun `toDomain sets vectorizedAt to null when not vectorized`() {
        val entity = createEntity(vectorized = 0, updatedAt = 5000L)
        val doc = DocumentMapper.toDomain(entity)
        assertThat(doc.vectorizedAt).isNull()
    }

    @Test
    fun `toDomain handles null fields`() {
        val entity = createEntity(
            title = null,
            content = null,
            folderId = null,
            contentHash = null,
            updatedAt = null
        )
        val doc = DocumentMapper.toDomain(entity)

        assertThat(doc.title).isEmpty()
        assertThat(doc.content).isEmpty()
        assertThat(doc.folderId).isEmpty()
        assertThat(doc.hash).isEmpty()
        assertThat(doc.updatedAt).isEqualTo(entity.createdAt)
    }

    @Test
    fun `toDomain handles zero timestamps`() {
        val entity = createEntity(createdAt = 0L, updatedAt = 0L)
        val doc = DocumentMapper.toDomain(entity)
        assertThat(doc.createdAt).isEqualTo(0L)
        assertThat(doc.updatedAt).isEqualTo(0L)
    }

    @Test
    fun `toEntity maps domain to entity correctly`() {
        val doc = Document(
            id = "doc-2",
            folderId = "folder-2",
            title = "Title",
            content = "Content",
            hash = "hash123",
            vectorized = 1,
            vectorizedAt = 3000L,
            createdAt = 1000L,
            updatedAt = 2000L
        )
        val entity = DocumentMapper.toEntity(doc)

        assertThat(entity.id).isEqualTo("doc-2")
        assertThat(entity.folderId).isEqualTo("folder-2")
        assertThat(entity.title).isEqualTo("Title")
        assertThat(entity.content).isEqualTo("Content")
        assertThat(entity.contentHash).isEqualTo("hash123")
        assertThat(entity.vectorized).isEqualTo(1)
        assertThat(entity.createdAt).isEqualTo(1000L)
        assertThat(entity.updatedAt).isEqualTo(2000L)
    }

    @Test
    fun `toEntity maps null vectorizedAt to 0`() {
        val doc = Document(
            id = "doc-3",
            folderId = "f",
            title = "T",
            content = "C",
            vectorizedAt = null
        )
        val entity = DocumentMapper.toEntity(doc)
        assertThat(entity.vectorized).isEqualTo(0)
    }

    @Test
    fun `roundtrip preserves core fields`() {
        val entity = createEntity(
            id = "roundtrip",
            title = "RT Doc",
            content = "RT Content",
            folderId = "folder-rt",
            contentHash = "rt-hash",
            vectorized = 1,
            createdAt = 100L,
            updatedAt = 200L
        )
        val domain = DocumentMapper.toDomain(entity)
        val back = DocumentMapper.toEntity(domain)

        assertThat(back.id).isEqualTo(entity.id)
        assertThat(back.title).isEqualTo(entity.title)
        assertThat(back.content).isEqualTo(entity.content)
        assertThat(back.folderId).isEqualTo(entity.folderId)
        assertThat(back.contentHash).isEqualTo(entity.contentHash)
        assertThat(back.vectorized).isEqualTo(entity.vectorized)
        assertThat(back.createdAt).isEqualTo(entity.createdAt)
        assertThat(back.updatedAt).isEqualTo(entity.updatedAt)
    }
}
