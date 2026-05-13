package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.DocumentDao
import com.promenar.nexara.data.local.db.dao.FolderDao
import com.promenar.nexara.data.local.db.entity.DocumentEntity
import com.promenar.nexara.data.mapper.DocumentMapper
import com.promenar.nexara.domain.model.Document
import com.promenar.nexara.domain.repository.IDocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.security.MessageDigest

class DocumentRepository(
    private val documentDao: DocumentDao,
    private val folderDao: FolderDao
) : IDocumentRepository {
    override fun observeAll(): Flow<List<Document>> =
        documentDao.observeAll().map { list ->
            list.map { DocumentMapper.toDomain(it) }
        }

    override fun observeByFolder(folderId: String): Flow<List<Document>> =
        documentDao.observeAll().map { list ->
            list.filter { it.folderId == folderId }.map { DocumentMapper.toDomain(it) }
        }

    override suspend fun getById(id: String): Document? =
        documentDao.getById(id)?.let { DocumentMapper.toDomain(it) }

    override suspend fun getByFolderId(folderId: String): List<Document> =
        documentDao.getByFolderId(folderId).map { DocumentMapper.toDomain(it) }

    override suspend fun getCount(): Int =
        documentDao.getCount()

    override suspend fun countByFolderId(folderId: String): Int =
        documentDao.countByFolderId(folderId)

    override suspend fun import(path: String, folderId: String): Document {
        val file = File(path)
        val content = file.readText()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        val id = "doc_$now"
        val entity = DocumentEntity(
            id = id,
            title = file.name,
            content = content,
            source = path,
            folderId = folderId,
            contentHash = hash,
            fileSize = file.length(),
            createdAt = now,
            updatedAt = now
        )
        documentDao.insert(entity)
        return DocumentMapper.toDomain(entity)
    }

    override suspend fun update(id: String, content: String) {
        val existing = documentDao.getById(id) ?: return
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        documentDao.update(
            existing.copy(
                content = content,
                contentHash = hash,
                updatedAt = now
            )
        )
    }

    override suspend fun delete(id: String) {
        documentDao.deleteById(id)
    }

    override suspend fun markVectorized(id: String) {
        documentDao.updateVectorized(id, 1)
    }
}
