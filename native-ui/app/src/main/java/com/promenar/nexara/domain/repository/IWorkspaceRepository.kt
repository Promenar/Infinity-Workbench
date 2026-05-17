package com.promenar.nexara.domain.repository

import com.promenar.nexara.data.local.db.entity.FileEntry
import kotlinx.coroutines.flow.Flow

interface IWorkspaceRepository {
    fun observeRoots(): Flow<List<FileEntry>>
    fun observeChildren(parentUuid: String): Flow<List<FileEntry>>
    fun observeRecycleBin(workspaceRootUuid: String): Flow<List<FileEntry>>
    suspend fun getByUuid(uuid: String): FileEntry?
    suspend fun createFile(
        uuid: String,
        name: String,
        content: String,
        parentUuid: String?,
        physicalRootPath: String,
        materializedPath: String
    ): FileEntry

    suspend fun createDirectory(
        uuid: String,
        name: String,
        parentUuid: String?,
        physicalRootPath: String,
        materializedPath: String
    ): FileEntry

    suspend fun moveToRecycleBin(uuid: String)
    suspend fun restoreFromRecycleBin(uuid: String)
    suspend fun permanentDelete(uuid: String)
    suspend fun emptyRecycleBin(workspaceRootUuid: String)
    suspend fun updateParent(uuid: String, newParentUuid: String)
    suspend fun getNextSeqForDate(dateKey: String): Int
    suspend fun resetAllRAGStatus()
}
