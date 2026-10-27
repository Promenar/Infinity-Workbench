package com.promenar.nexara.data.artifact

import com.promenar.nexara.data.canvas.CanvasStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 只含画布产物的过渡实现：文件快照与工作区候选尚未接入时使用。 */
class CanvasOnlySessionArtifacts(private val canvases: CanvasStore) : SessionArtifactRepository {
    override fun observe(sessionId: String): Flow<List<ArtifactSummary>> =
        canvases.observe(sessionId).map { list -> list.map { it.toArtifactSummary() }.sortedByDescending(ArtifactSummary::updatedAt) }

    override suspend fun publishFile(
        sessionId: String,
        path: String,
        title: String?,
        kind: ArtifactKind?,
        origin: ArtifactOrigin,
        messageId: String?,
    ): ArtifactSummary = throw ArtifactException("UNSUPPORTED", "当前版本不支持文件产物。")

    override suspend fun readFile(sessionId: String, id: String, version: Int?): ArtifactFileSnapshot? = null

    override suspend fun candidates(sessionId: String, changes: List<WorkspaceChangedFile>): List<ArtifactCandidate> = emptyList()

    override suspend fun ignoreCandidate(sessionId: String, path: String) = Unit
}
