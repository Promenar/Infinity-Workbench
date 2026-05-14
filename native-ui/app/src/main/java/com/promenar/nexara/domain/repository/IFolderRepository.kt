package com.promenar.nexara.domain.repository

import com.promenar.nexara.domain.model.Folder
import kotlinx.coroutines.flow.Flow

interface IFolderRepository {
    fun observeAll(): Flow<List<Folder>>
    suspend fun getById(id: String): Folder?
    suspend fun create(folder: Folder)
    suspend fun update(folder: Folder)
    suspend fun delete(folder: Folder)
}
