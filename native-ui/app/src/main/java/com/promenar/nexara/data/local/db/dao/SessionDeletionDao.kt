package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query

/** 会话删除不依赖外键级联；每个关联面都由事务显式清理。 */
@Dao
interface SessionDeletionDao {
    @Query("DELETE FROM attachments WHERE message_id IN (SELECT id FROM messages WHERE session_id = :sessionId)")
    suspend fun deleteAttachments(sessionId: String): Int

    @Query("DELETE FROM tool_execution_ledger WHERE session_id = :sessionId")
    suspend fun deleteToolLedger(sessionId: String): Int

    @Query("DELETE FROM artifacts WHERE session_id = :sessionId")
    suspend fun deleteArtifacts(sessionId: String): Int

    @Query("DELETE FROM context_summaries WHERE session_id = :sessionId")
    suspend fun deleteContextSummaries(sessionId: String): Int

    @Query("DELETE FROM task_nodes WHERE session_id = :sessionId")
    suspend fun deleteTaskNodes(sessionId: String): Int

    @Query("DELETE FROM audit_logs WHERE session_id = :sessionId")
    suspend fun deleteAuditLogs(sessionId: String): Int

    @Query("DELETE FROM vectorization_tasks WHERE session_id = :sessionId OR workspace_root_uuid = :workspaceRootUuid")
    suspend fun deleteVectorizationTasks(sessionId: String, workspaceRootUuid: String): Int

    @Query("DELETE FROM vectors WHERE session_id = :sessionId OR doc_id IN (:fileUuids) OR file_uuid IN (:fileUuids)")
    suspend fun deleteVectors(sessionId: String, fileUuids: List<String>): Int

    @Query("DELETE FROM document_tags WHERE doc_id IN (:fileUuids)")
    suspend fun deleteDocumentTags(fileUuids: List<String>): Int

    @Query("DELETE FROM kg_edges WHERE session_id = :sessionId OR doc_id IN (:fileUuids) OR file_uuid IN (:fileUuids)")
    suspend fun deleteKgEdges(sessionId: String, fileUuids: List<String>): Int

    @Query("DELETE FROM kg_nodes WHERE session_id = :sessionId OR file_uuid IN (:fileUuids)")
    suspend fun deleteKgNodes(sessionId: String, fileUuids: List<String>): Int

    @Query("DELETE FROM kg_jit_cache")
    suspend fun invalidateKgJitCache(): Int

    @Query("DELETE FROM file_versions WHERE workspace_root_uuid = :workspaceRootUuid OR created_by_session_id = :sessionId")
    suspend fun deleteFileVersions(sessionId: String, workspaceRootUuid: String): Int

    @Query("DELETE FROM messages WHERE session_id = :sessionId")
    suspend fun deleteMessages(sessionId: String): Int

    @Query("DELETE FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid")
    suspend fun deleteWorkspaceFiles(workspaceRootUuid: String): Int

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String): Int
}
