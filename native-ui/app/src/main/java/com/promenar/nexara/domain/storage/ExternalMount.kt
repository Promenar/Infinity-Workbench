package com.promenar.nexara.domain.storage

import kotlinx.coroutines.flow.Flow

/** 挂载来源不进入工具参数；模型仅能使用会话可见的虚拟路径。 */
data class ExternalMount(
    val id: String,
    val alias: String,
    val sourceType: String,
    val sourceId: String,
    val sessionId: String?,
    val readOnly: Boolean,
    val createdAt: Long,
    val unavailableReason: String? = null,
)

data class ExternalRecycleItem(
    val id: String,
    val mountId: String,
    val originalPath: String,
    val deletedAt: Long,
    val size: Long,
)

/** 文件面板与工具共用挂载管理，所有涉及文件与数据库的方法自行切换到 IO。 */
interface IExternalFolderManager {
    fun observeMounts(sessionId: String): Flow<List<ExternalMount>>
    suspend fun addSaf(alias: String, sessionId: String?, treeUri: String, grantFlags: Int, readOnly: Boolean): ExternalMount
    suspend fun addRealPath(alias: String, sessionId: String?, source: String, readOnly: Boolean): ExternalMount
    suspend fun remove(mountId: String)
    suspend fun reauthorize(mountId: String, treeUri: String, grantFlags: Int)
    suspend fun refresh(sessionId: String)
    suspend fun list(sessionId: String, virtualPath: String): List<ExternalFolderEntry>
    suspend fun read(sessionId: String, virtualPath: String, maxBytes: Long = 1024L * 1024): ByteArray
    suspend fun recycleItems(sessionId: String): List<ExternalRecycleItem>
    suspend fun restore(sessionId: String, recycleId: String)
    suspend fun importSnapshot(sessionId: String, workspaceRootUuid: String, virtualPath: String): String
    suspend fun snapshotChanged(sessionId: String, virtualPath: String): Boolean
}
