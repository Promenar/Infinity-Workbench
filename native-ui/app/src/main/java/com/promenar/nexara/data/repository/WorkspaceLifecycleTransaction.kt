package com.promenar.nexara.data.repository

import androidx.room.withTransaction
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity
import com.promenar.nexara.data.rag.RoomDocumentArtifacts
import com.promenar.nexara.data.rag.VectorizationQueue
import com.promenar.nexara.domain.repository.RenameIndexTarget
import com.promenar.nexara.infra.util.Sha256Utils

/** 回收、恢复的元数据与派生数据事务边界。物理移动由 WorkspaceRepository 先 stage。 */
class WorkspaceLifecycleTransaction(
    private val database: NexaraDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun recycle(
        workspaceRootUuid: String,
        updates: List<FileEntry>,
        fileUuids: List<String>,
    ) {
        database.withTransaction {
            val artifacts = RoomDocumentArtifacts(database)
            fileUuids.forEach { fileUuid ->
                artifacts.clear(workspaceRootUuid, fileUuid, deleteTags = false)
            }
            database.vectorizationTaskDao().deleteByWorkspaceFiles(workspaceRootUuid, fileUuids)
            database.kgJitCacheDao().deleteAll()
            database.fileEntryDao().updateAll(updates)
        }
    }

    suspend fun restore(
        workspaceRootUuid: String,
        updates: List<FileEntry>,
        rebuildTargets: List<RenameIndexTarget>,
    ) {
        database.withTransaction {
            database.fileEntryDao().updateAll(updates)
            val files = updates.associateBy { it.uuid }
            rebuildTargets.forEach { target ->
                val entry = requireNotNull(files[target.fileUuid]) { "恢复重建目标不在提交子树" }
                check(!entry.isDirectory && !entry.inRecycleBin) { "恢复重建目标必须是 active 文件" }
                database.vectorizationTaskDao().upsertTarget(
                    VectorizationTaskEntity(
                        id = restoreTaskId(workspaceRootUuid, target),
                        type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                        status = "pending",
                        docId = entry.uuid,
                        docTitle = entry.name,
                        workspaceRootUuid = workspaceRootUuid,
                        sourceMimeType = entry.mimeType,
                        targetContentHash = target.targetHash,
                        targetEpoch = target.targetEpoch,
                        createdAt = now(),
                        updatedAt = now(),
                    ),
                )
            }
        }
    }

    private fun restoreTaskId(workspaceRootUuid: String, target: RenameIndexTarget): String =
        "restore-${Sha256Utils.hash("$workspaceRootUuid:${target.fileUuid}:${target.targetHash}:${target.targetEpoch}").take(32)}"
}
