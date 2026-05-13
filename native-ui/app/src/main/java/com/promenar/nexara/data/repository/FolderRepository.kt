package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.FolderDao
import com.promenar.nexara.data.mapper.FolderMapper
import com.promenar.nexara.domain.model.Folder
import com.promenar.nexara.domain.repository.IFolderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FolderRepository(
    private val folderDao: FolderDao
) : IFolderRepository {

    override fun observeAll(): Flow<List<Folder>> =
        folderDao.observeAll().map { list -> list.map { FolderMapper.toDomain(it) } }

    override suspend fun getById(id: String): Folder? =
        folderDao.getById(id)?.let { FolderMapper.toDomain(it) }

    override suspend fun create(folder: Folder) {
        folderDao.insert(FolderMapper.toEntity(folder))
    }

    override suspend fun delete(folder: Folder) {
        folderDao.delete(FolderMapper.toEntity(folder))
    }
}
