package com.promenar.nexara.data.mapper

import com.promenar.nexara.data.local.db.entity.FolderEntity
import com.promenar.nexara.domain.model.Folder

object FolderMapper {
    fun toDomain(entity: FolderEntity): Folder = Folder(
        id = entity.id,
        name = entity.name,
        parentId = entity.parentId,
        createdAt = entity.createdAt
    )

    fun toEntity(folder: Folder): FolderEntity = FolderEntity(
        id = folder.id,
        name = folder.name,
        parentId = folder.parentId,
        createdAt = folder.createdAt
    )
}
