package com.promenar.nexara.domain.repository

import com.promenar.nexara.data.local.db.entity.FileEntry
import kotlinx.coroutines.flow.Flow

interface IWorkspaceRepository {
    suspend fun ensureSessionRoot(sessionId: String): FileEntry
    suspend fun ensureSessionRoot(sessionId: String, physicalRootPath: String): FileEntry
    fun observeRoots(workspaceRootUuid: String): Flow<List<FileEntry>>
    fun observeChildren(workspaceRootUuid: String, parentUuid: String): Flow<List<FileEntry>>
    suspend fun getByUuid(workspaceRootUuid: String, uuid: String): FileEntry?
    suspend fun getByMaterializedPath(workspaceRootUuid: String, materializedPath: String): FileEntry?
    suspend fun getSubtree(
        workspaceRootUuid: String,
        materializedPathPrefix: String,
        inRecycleBin: Boolean = false,
    ): List<FileEntry>
    fun searchByName(workspaceRootUuid: String, query: String): Flow<List<FileEntry>>

    @Deprecated("Task3B 前的失败关闭兼容入口；必须传 workspaceRootUuid")
    fun observeRoots(): Flow<List<FileEntry>>
    @Deprecated("Task3B 前的失败关闭兼容入口；必须传 workspaceRootUuid")
    fun observeChildren(parentUuid: String): Flow<List<FileEntry>>
    fun observeRecycleBin(workspaceRootUuid: String): Flow<List<FileEntry>>
    @Deprecated("Task3B 前的失败关闭兼容入口；必须传 workspaceRootUuid")
    suspend fun getByUuid(uuid: String): FileEntry?
    suspend fun createFileInWorkspace(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        content: String,
        parentUuid: String?,
        materializedPath: String
    ): FileEntry

    @Deprecated("Task3B 前的失败关闭兼容入口；必须传 workspaceRootUuid")
    suspend fun createFile(
        uuid: String,
        name: String,
        content: String,
        parentUuid: String?,
        physicalRootPath: String,
        materializedPath: String
    ): FileEntry

    suspend fun createDirectoryInWorkspace(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        parentUuid: String?,
        materializedPath: String
    ): FileEntry

    @Deprecated("Task3B 前的失败关闭兼容入口；必须传 workspaceRootUuid")
    suspend fun createDirectory(
        uuid: String,
        name: String,
        parentUuid: String?,
        physicalRootPath: String,
        materializedPath: String
    ): FileEntry

    suspend fun moveToRecycleBin(workspaceRootUuid: String, uuid: String)
    suspend fun restoreFromRecycleBin(workspaceRootUuid: String, uuid: String)
    suspend fun permanentDelete(workspaceRootUuid: String, uuid: String)
    @Deprecated("Task3B 前的失败关闭兼容入口；必须传 workspaceRootUuid")
    suspend fun moveToRecycleBin(uuid: String)
    @Deprecated("Task3B 前的失败关闭兼容入口；必须传 workspaceRootUuid")
    suspend fun restoreFromRecycleBin(uuid: String)
    @Deprecated("Task3B 前的失败关闭兼容入口；必须传 workspaceRootUuid")
    suspend fun permanentDelete(uuid: String)
    suspend fun emptyRecycleBin(workspaceRootUuid: String)
    suspend fun updateParent(workspaceRootUuid: String, uuid: String, newParentUuid: String)
    suspend fun rename(workspaceRootUuid: String, uuid: String, newName: String)
    @Deprecated("Task3B 前的失败关闭兼容入口；必须传 workspaceRootUuid")
    suspend fun updateParent(uuid: String, newParentUuid: String)
    suspend fun getNextSeqForDate(dateKey: String): Int
    suspend fun resetAllRAGStatus(workspaceRootUuid: String)
    @Deprecated("Task3B 前的失败关闭兼容入口；必须传 workspaceRootUuid")
    suspend fun resetAllRAGStatus()
}
