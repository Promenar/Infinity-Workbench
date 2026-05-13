package com.promenar.nexara.data.mapper

import com.promenar.nexara.data.local.db.entity.DocumentEntity
import com.promenar.nexara.domain.model.Document

object DocumentMapper {
    fun toDomain(entity: DocumentEntity): Document = Document(
        id = entity.id,
        folderId = entity.folderId ?: "",
        title = entity.title ?: "",
        content = entity.content ?: "",
        hash = entity.contentHash ?: "",
        source = entity.source,
        fileSize = entity.fileSize,
        vectorized = entity.vectorized,
        vectorizedAt = if (entity.vectorized == 1) entity.updatedAt else null,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt ?: entity.createdAt
    )

    fun toEntity(document: Document): DocumentEntity = DocumentEntity(
        id = document.id,
        title = document.title,
        content = document.content,
        source = document.source,
        folderId = document.folderId,
        contentHash = document.hash,
        fileSize = document.fileSize,
        vectorized = document.vectorized,
        createdAt = document.createdAt,
        updatedAt = document.updatedAt ?: document.createdAt
    )
}
