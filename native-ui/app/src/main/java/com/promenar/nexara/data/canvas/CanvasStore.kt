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
)

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
    suspend fun deleteSession(sessionId: String)
    suspend fun copySession(sourceSessionId: String, targetSessionId: String, messageIdMap: Map<String, String>)
}
