package com.promenar.nexara.data.repository

import androidx.room.withTransaction
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.rag.RoomDocumentArtifacts

/** 将文件记录删除与全部派生数据清理纳入同一 Room 事务。物理文件由仓储先 stage，事务失败时统一回滚。 */
class WorkspaceDeletionTransaction(
    private val database: NexaraDatabase,
    private val beforeFileDelete: suspend () -> Unit = {},
) {
    suspend fun delete(workspaceRootUuid: String, fileUuids: List<String>) {
        database.withTransaction {
            val artifacts = RoomDocumentArtifacts(database)
            fileUuids.forEach { fileUuid ->
                artifacts.clear(workspaceRootUuid, fileUuid, deleteTags = true)
            }
            beforeFileDelete()
            database.fileEntryDao().deleteByUuids(workspaceRootUuid, fileUuids)
        }
    }
}
