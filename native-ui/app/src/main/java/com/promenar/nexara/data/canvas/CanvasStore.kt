package com.promenar.nexara.data.canvas

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class CanvasMetadata(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val currentVersion: Int,
    val createdByMessageId: String,
    val versions: List<Int>,
    val workspacePath: String? = null,
    val workspaceSync: CanvasWorkspaceSync = CanvasWorkspaceSync.NOT_WRITTEN,
    val versionSources: Map<Int, CanvasVersionSource> = emptyMap(),
)

@Serializable
enum class CanvasWorkspaceSync { SYNCED, FAILED, MISSING, NOT_WRITTEN }

@Serializable
enum class CanvasVersionSource { CANVAS, WORKSPACE }

data class CanvasDocument(val metadata: CanvasMetadata, val html: String, val version: Int)

data class CanvasPatch(val find: String, val replace: String)

class CanvasException(val code: String, override val message: String) : Exception(message)

interface CanvasStore {
    suspend fun list(sessionId: String): List<CanvasMetadata>
    fun observe(sessionId: String): Flow<List<CanvasMetadata>>
    suspend fun create(sessionId: String, title: String, html: String, createdByMessageId: String): CanvasDocument
    suspend fun update(
        sessionId: String,
        id: String,
        html: String? = null,
        patches: List<CanvasPatch>? = null,
        expectedVersion: Int? = null,
    ): CanvasDocument
    suspend fun read(sessionId: String, id: String, version: Int? = null): CanvasDocument?
    /** 导入工作区修改；缺失时保留私有版本，未写出或失败时重试同步。 */
    suspend fun syncFromWorkspace(sessionId: String, id: String): CanvasDocument?
    suspend fun deleteSession(sessionId: String)
    suspend fun copySession(sourceSessionId: String, targetSessionId: String, messageIdMap: Map<String, String>)
}
