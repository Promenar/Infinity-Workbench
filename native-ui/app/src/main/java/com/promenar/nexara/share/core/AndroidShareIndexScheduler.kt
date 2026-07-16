package com.promenar.nexara.share.core

import android.content.Context
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.usecase.RagConfigPersistence

/** 只负责同步持久化 file-reference 任务；实际读取与索引由可恢复队列后台完成。 */
class AndroidShareIndexScheduler(
    private val app: NexaraApplication,
) : ShareIndexScheduler {
    override suspend fun schedule(workspaceRootUuid: String, entry: FileEntry): ShareIndexReceipt {
        val retrieval = RagConfigPersistence(
            app.getSharedPreferences("rag_settings", Context.MODE_PRIVATE)
        ).loadRetrievalConfig()
        // recordCreated/ack 失败后的幂等重放可能复用已完成索引的同哈希文件，不应再次向量化。
        val vectorCurrent = entry.vectorizedAt?.let { it >= entry.updatedAt } == true
        val graphCurrent = !retrieval.enableKnowledgeGraph ||
            entry.kgExtractedAt?.let { it >= entry.updatedAt } == true
        if (vectorCurrent && graphCurrent) {
            return ShareIndexReceipt(taskId = "completed-${entry.uuid}", fileUuid = entry.uuid)
        }
        val taskId = app.vectorizationQueue.enqueueDocumentReference(
            workspaceRootUuid = workspaceRootUuid,
            docId = entry.uuid,
            docTitle = entry.name,
            sourceMimeType = requireNotNull(entry.mimeType) { "索引文件缺少 MIME" },
            targetContentHash = entry.hash,
            targetEpoch = entry.updatedAt,
            kgStrategy = if (retrieval.enableKnowledgeGraph) "full" else null,
        )
        return ShareIndexReceipt(taskId = taskId, fileUuid = entry.uuid)
    }
}
