package com.promenar.nexara.data.rag

import androidx.room.withTransaction
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.KgEdgeEntity
import com.promenar.nexara.data.local.db.entity.KgNodeEntity
import com.promenar.nexara.data.local.db.entity.VectorEntity
import com.promenar.nexara.data.local.db.dao.FileEntryDao
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

interface DocumentIndexService {
    suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult
    suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult
    suspend fun prepareLegacyRetry(
        workspaceRootUuid: String,
        fileUuid: String,
        legacyTaskId: String,
    ): Boolean = false
}

sealed interface DocumentIndexResult {
    data class Rebuilt(val fileUuid: String) : DocumentIndexResult
    data class Deleted(val fileUuid: String) : DocumentIndexResult
    data class HashChanged(val currentHash: String?) : DocumentIndexResult
    data class Failed(val failure: Throwable) : DocumentIndexResult
}

data class DocumentIndexCandidate(
    val vectors: List<VectorEntity>,
    val nodes: List<KgNodeEntity> = emptyList(),
    val edges: List<KgEdgeEntity> = emptyList(),
)

fun interface DocumentIndexCandidateBuilder {
    suspend fun build(event: FileIndexEvent.Changed): DocumentIndexCandidate
}

fun interface KnowledgeGraphCandidateBuilder {
    suspend fun build(text: String, fileUuid: String): ExtractionResult
}

class GraphExtractorKnowledgeGraphCandidateBuilder(
    private val graphExtractor: GraphExtractor,
) : KnowledgeGraphCandidateBuilder {
    override suspend fun build(text: String, fileUuid: String): ExtractionResult =
        graphExtractor.extractCandidate(text, fileUuid)
}

class WorkspaceDocumentIndexCandidateBuilder(
    private val fileEntryDao: FileEntryDao,
    private val embeddingClient: EmbeddingClient,
    private val ragConfig: RagConfiguration,
    private val now: () -> Long = System::currentTimeMillis,
    private val graphCandidateBuilder: KnowledgeGraphCandidateBuilder? = null,
) : DocumentIndexCandidateBuilder {
    override suspend fun build(event: FileIndexEvent.Changed): DocumentIndexCandidate {
        val entry = fileEntryDao.getByUuid(event.workspaceRootUuid, event.fileUuid)
            ?: throw java.io.FileNotFoundException("索引文件不存在")
        require(entry.hash == event.contentHash) { "索引事件 hash 已过期" }
        require(entry.updatedAt == event.targetEpoch) { "索引事件 epoch 已过期" }
        val file = File(entry.physicalRootPath, entry.materializedPath.trimStart('/')).canonicalFile
        val before = file.sha256()
        require(before == event.contentHash) { "索引前文件内容与数据库 hash 不一致" }
        val extraction = DocumentReferenceExtractor(
            chunkSize = ragConfig.docChunkSize,
            chunkOverlap = ragConfig.chunkOverlap,
        ).extract(entry)
        val embedding = if (event.skipVectorization || extraction.chunks.isEmpty()) emptyList()
        else embeddingClient.embedDocuments(extraction.chunks).embeddings
        require(event.skipVectorization || embedding.size == extraction.chunks.size) { "Embedding 数量与切块不一致" }
        val shouldExtractGraph = event.kgStrategy != null ||
            (event.useConfiguredKgStrategy && ragConfig.enableKnowledgeGraph)
        val graph = if (shouldExtractGraph) {
            val builder = requireNotNull(graphCandidateBuilder) { "启用知识图谱后必须配置候选构建器" }
            builder.build(extraction.graphText, event.fileUuid).also {
                require(it.error == null) { "知识图谱候选构建失败: ${it.error}" }
            }
        } else null
        require(file.sha256() == event.contentHash) { "候选构建期间文件内容已变化" }
        val timestamp = now()
        val nodeIds = graph?.nodes.orEmpty().associate { it.name.trim().lowercase() to UUID.randomUUID().toString() }
        return DocumentIndexCandidate(
            vectors = if (event.skipVectorization) emptyList() else extraction.chunks.mapIndexed { index, content ->
                VectorEntity(
                    id = UUID.randomUUID().toString(),
                    docId = event.fileUuid,
                    content = content,
                    embedding = embedding[index].toBlob(),
                    metadata = documentVectorMetadata(event.fileUuid, index, entry.name),
                    startMessageId = event.fileUuid,
                    endMessageId = event.fileUuid,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    stale = false,
                    fileUuid = event.fileUuid,
                )
            },
            nodes = graph?.nodes.orEmpty().mapNotNull { node ->
                nodeIds[node.name.trim().lowercase()]?.let { id ->
                    KgNodeEntity(
                        id = id,
                        name = node.name,
                        type = node.type,
                        metadata = node.metadata,
                        createdAt = timestamp,
                        updatedAt = timestamp,
                        fileUuid = event.fileUuid,
                    )
                }
            },
            edges = graph?.edges.orEmpty().mapNotNull { edge ->
                val sourceId = nodeIds[edge.source.trim().lowercase()]
                val targetId = nodeIds[edge.target.trim().lowercase()]
                if (sourceId == null || targetId == null) null else KgEdgeEntity(
                    id = UUID.randomUUID().toString(),
                    sourceId = sourceId,
                    targetId = targetId,
                    relation = edge.relation,
                    weight = edge.weight,
                    docId = event.fileUuid,
                    createdAt = timestamp,
                    fileUuid = event.fileUuid,
                )
            },
        )
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun FloatArray.toBlob(): ByteArray = ByteBuffer.allocate(size * Float.SIZE_BYTES)
        .also { it.asFloatBuffer().put(this) }
        .array()
}

class RoomDocumentIndexService(
    private val database: NexaraDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val candidateBuilder: DocumentIndexCandidateBuilder,
) : DocumentIndexService {
    private val artifacts = RoomDocumentArtifacts(database)
    override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult {
        if (event is FileIndexEvent.Deleted) return delete(event.workspaceRootUuid, event.fileUuid)
        event as FileIndexEvent.Changed
        val activeTaskId = event.activeTaskId
            ?: return DocumentIndexResult.Failed(
                IllegalArgumentException("索引提交缺少活动任务标识"),
            )
        val candidate = try {
            candidateBuilder.build(event)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return DocumentIndexResult.Failed(failure)
        }
        return try {
            database.withTransaction {
                val current = database.fileEntryDao().getByUuid(event.workspaceRootUuid, event.fileUuid)
                if (current?.hash != event.contentHash || current.updatedAt != event.targetEpoch) {
                    return@withTransaction DocumentIndexResult.HashChanged(current?.hash)
                }
                val activeTask = database.vectorizationTaskDao().getByWorkspaceFile(
                    event.workspaceRootUuid,
                    event.fileUuid,
                    DOCUMENT_REFERENCE_TYPE,
                )
                if (activeTask?.id != activeTaskId ||
                    activeTask.targetContentHash != event.contentHash ||
                    activeTask.targetEpoch != event.targetEpoch
                ) {
                    return@withTransaction DocumentIndexResult.HashChanged(current.hash)
                }
                artifacts.clear(event.workspaceRootUuid, event.fileUuid, deleteTags = false)
                if (candidate.vectors.isNotEmpty()) database.vectorDao().insertAll(candidate.vectors)
                candidate.nodes.forEach { database.kgNodeDao().insert(it) }
                candidate.edges.forEach { database.kgEdgeDao().insert(it) }
                val timestamp = maxOf(now(), event.targetEpoch)
                database.fileEntryDao().update(current.copy(
                    vectorizedAt = timestamp,
                    kgExtractedAt = timestamp.takeIf { event.kgStrategy != null },
                ))
                DocumentIndexResult.Rebuilt(event.fileUuid)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            DocumentIndexResult.Failed(failure)
        }
    }

    override suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult = try {
        database.withTransaction {
            artifacts.clear(workspaceRootUuid, fileUuid, deleteTags = true)
            database.vectorizationTaskDao().deleteByWorkspaceFile(workspaceRootUuid, fileUuid)
            DocumentIndexResult.Deleted(fileUuid)
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        DocumentIndexResult.Failed(failure)
    }

    override suspend fun prepareLegacyRetry(
        workspaceRootUuid: String,
        fileUuid: String,
        legacyTaskId: String,
    ): Boolean = try {
        database.withTransaction {
            val task = database.vectorizationTaskDao().getById(legacyTaskId)
                ?: return@withTransaction false
            if (task.type != "document" || task.workspaceRootUuid != workspaceRootUuid || task.docId != fileUuid) {
                return@withTransaction false
            }
            artifacts.clear(workspaceRootUuid, fileUuid, deleteTags = false)
            database.vectorizationTaskDao().update(
                task.copy(
                    status = "interrupted",
                    error = null,
                    subStatus = "删除回滚，等待安全重建",
                    updatedAt = now(),
                ),
            ) == 1
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

}

private const val DOCUMENT_REFERENCE_TYPE = "document_reference"

class RoomDocumentArtifacts(
    private val database: NexaraDatabase,
) {
    suspend fun clear(workspaceRootUuid: String, fileUuid: String, deleteTags: Boolean) {
        database.vectorDao().deleteByDocId(fileUuid)
        database.kgEdgeDao().deleteByDocId(fileUuid)
        database.kgNodeDao().deleteByFileUuid(fileUuid)
        if (deleteTags) database.documentTagDao().deleteByDocId(fileUuid)
    }
}
