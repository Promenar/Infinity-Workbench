package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity

@Dao
interface VectorizationTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: VectorizationTaskEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(task: VectorizationTaskEntity): Long

    @Update
    suspend fun update(task: VectorizationTaskEntity): Int

    @Delete
    suspend fun delete(task: VectorizationTaskEntity)

    @Query("SELECT * FROM vectorization_tasks WHERE id = :taskId")
    suspend fun getById(taskId: String): VectorizationTaskEntity?

    @Query("""
        SELECT * FROM vectorization_tasks
        WHERE status = :status
          AND (
            doc_id IS NULL OR workspace_root_uuid IS NULL OR EXISTS (
              SELECT 1 FROM workspace_files AS f
              WHERE f.workspace_root_uuid = vectorization_tasks.workspace_root_uuid
                AND f.uuid = vectorization_tasks.doc_id
                AND f.in_recycle_bin = 0
            )
          )
    """)
    suspend fun getByStatus(status: String): List<VectorizationTaskEntity>

    @Query("""
        SELECT * FROM vectorization_tasks
        WHERE doc_id = :docId
          AND EXISTS (
            SELECT 1 FROM workspace_files AS f
            WHERE f.workspace_root_uuid = vectorization_tasks.workspace_root_uuid
              AND f.uuid = vectorization_tasks.doc_id
              AND f.in_recycle_bin = 0
          )
    """)
    suspend fun getByDocId(docId: String): List<VectorizationTaskEntity>

    @Query("""
        SELECT * FROM vectorization_tasks
        WHERE workspace_root_uuid = :workspaceRootUuid AND doc_id IN (:docIds)
          AND EXISTS (
            SELECT 1 FROM workspace_files AS f
            WHERE f.workspace_root_uuid = vectorization_tasks.workspace_root_uuid
              AND f.uuid = vectorization_tasks.doc_id
              AND f.in_recycle_bin = 0
          )
    """)
    suspend fun getByWorkspaceFiles(
        workspaceRootUuid: String,
        docIds: List<String>,
    ): List<VectorizationTaskEntity>

    @Query("""
        SELECT * FROM vectorization_tasks
        WHERE workspace_root_uuid = :workspaceRootUuid AND doc_id = :docId AND type = :type
          AND EXISTS (
            SELECT 1 FROM workspace_files AS f
            WHERE f.workspace_root_uuid = vectorization_tasks.workspace_root_uuid
              AND f.uuid = vectorization_tasks.doc_id
              AND f.in_recycle_bin = 0
          )
        LIMIT 1
    """)
    suspend fun getByWorkspaceFile(workspaceRootUuid: String, docId: String, type: String): VectorizationTaskEntity?

    @Query("""
        SELECT COUNT(*) FROM vectorization_tasks
        WHERE workspace_root_uuid = :workspaceRootUuid
          AND doc_id = :docId
          AND status IN ('pending', 'processing', 'extracting_source', 'chunking', 'vectorizing', 'saving', 'extracting')
          AND EXISTS (
            SELECT 1 FROM workspace_files AS f
            WHERE f.workspace_root_uuid = vectorization_tasks.workspace_root_uuid
              AND f.uuid = vectorization_tasks.doc_id
              AND f.in_recycle_bin = 0
          )
    """)
    suspend fun countActiveForFile(workspaceRootUuid: String, docId: String): Int

    @Query("SELECT * FROM vectorization_tasks WHERE session_id = :sessionId")
    suspend fun getBySessionId(sessionId: String): List<VectorizationTaskEntity>

    @Query("UPDATE vectorization_tasks SET status = :status, updated_at = :updatedAt WHERE id = :taskId")
    suspend fun updateStatus(taskId: String, status: String, updatedAt: Long)

    @Query("""
        UPDATE vectorization_tasks
        SET status = :status, updated_at = :updatedAt
        WHERE id = :taskId
          AND target_content_hash IS :targetContentHash
          AND target_epoch = :targetEpoch
    """)
    suspend fun updateStatusForTarget(
        taskId: String,
        targetContentHash: String,
        targetEpoch: Long,
        status: String,
        updatedAt: Long,
    ): Int

    @Query("""
        DELETE FROM vectorization_tasks
        WHERE id = :taskId
          AND target_content_hash IS :targetContentHash
          AND target_epoch = :targetEpoch
    """)
    suspend fun deleteForTarget(taskId: String, targetContentHash: String?, targetEpoch: Long): Int

    @Query("""
        UPDATE vectorization_tasks
        SET id = :replacementId,
            status = :replacementStatus,
            doc_title = :replacementDocTitle,
            session_id = :replacementSessionId,
            user_content = :replacementUserContent,
            ai_content = :replacementAiContent,
            user_message_id = :replacementUserMessageId,
            assistant_message_id = :replacementAssistantMessageId,
            last_chunk_index = :replacementLastChunkIndex,
            total_chunks = :replacementTotalChunks,
            progress = :replacementProgress,
            error = :replacementError,
            kg_strategy = :replacementKgStrategy,
            skip_vectorization = :replacementSkipVectorization,
            sub_status = :replacementSubStatus,
            source_mime_type = :replacementSourceMimeType,
            content_truncated = :replacementContentTruncated,
            target_content_hash = :replacementTargetContentHash,
            target_epoch = :replacementTargetEpoch,
            created_at = :replacementCreatedAt,
            updated_at = :replacementUpdatedAt
        WHERE workspace_root_uuid IS :workspaceRootUuid
          AND doc_id IS :docId
          AND type = :type
          AND id = :expectedTaskId
          AND target_content_hash IS :expectedTargetContentHash
          AND target_epoch = :expectedTargetEpoch
    """)
    suspend fun replaceTargetIfExpectedRaw(
        workspaceRootUuid: String?,
        docId: String?,
        type: String,
        expectedTaskId: String,
        expectedTargetContentHash: String?,
        expectedTargetEpoch: Long,
        replacementId: String,
        replacementStatus: String,
        replacementDocTitle: String?,
        replacementSessionId: String?,
        replacementUserContent: String?,
        replacementAiContent: String?,
        replacementUserMessageId: String?,
        replacementAssistantMessageId: String?,
        replacementLastChunkIndex: Int,
        replacementTotalChunks: Int?,
        replacementProgress: Double,
        replacementError: String?,
        replacementKgStrategy: String?,
        replacementSkipVectorization: Boolean,
        replacementSubStatus: String?,
        replacementSourceMimeType: String?,
        replacementContentTruncated: Boolean,
        replacementTargetContentHash: String?,
        replacementTargetEpoch: Long,
        replacementCreatedAt: Long,
        replacementUpdatedAt: Long,
    ): Int

    suspend fun replaceTargetIfExpected(
        expected: VectorizationTaskEntity,
        replacement: VectorizationTaskEntity,
    ): Int = replaceTargetIfExpectedRaw(
        workspaceRootUuid = expected.workspaceRootUuid,
        docId = expected.docId,
        type = expected.type,
        expectedTaskId = expected.id,
        expectedTargetContentHash = expected.targetContentHash,
        expectedTargetEpoch = expected.targetEpoch,
        replacementId = replacement.id,
        replacementStatus = replacement.status,
        replacementDocTitle = replacement.docTitle,
        replacementSessionId = replacement.sessionId,
        replacementUserContent = replacement.userContent,
        replacementAiContent = replacement.aiContent,
        replacementUserMessageId = replacement.userMessageId,
        replacementAssistantMessageId = replacement.assistantMessageId,
        replacementLastChunkIndex = replacement.lastChunkIndex,
        replacementTotalChunks = replacement.totalChunks,
        replacementProgress = replacement.progress,
        replacementError = replacement.error,
        replacementKgStrategy = replacement.kgStrategy,
        replacementSkipVectorization = replacement.skipVectorization,
        replacementSubStatus = replacement.subStatus,
        replacementSourceMimeType = replacement.sourceMimeType,
        replacementContentTruncated = replacement.contentTruncated,
        replacementTargetContentHash = replacement.targetContentHash,
        replacementTargetEpoch = replacement.targetEpoch,
        replacementCreatedAt = replacement.createdAt,
        replacementUpdatedAt = replacement.updatedAt,
    )

    suspend fun updateForTarget(task: VectorizationTaskEntity): Int = replaceTargetIfExpectedRaw(
        workspaceRootUuid = task.workspaceRootUuid,
        docId = task.docId,
        type = task.type,
        expectedTaskId = task.id,
        expectedTargetContentHash = task.targetContentHash,
        expectedTargetEpoch = task.targetEpoch,
        replacementId = task.id,
        replacementStatus = task.status,
        replacementDocTitle = task.docTitle,
        replacementSessionId = task.sessionId,
        replacementUserContent = task.userContent,
        replacementAiContent = task.aiContent,
        replacementUserMessageId = task.userMessageId,
        replacementAssistantMessageId = task.assistantMessageId,
        replacementLastChunkIndex = task.lastChunkIndex,
        replacementTotalChunks = task.totalChunks,
        replacementProgress = task.progress,
        replacementError = task.error,
        replacementKgStrategy = task.kgStrategy,
        replacementSkipVectorization = task.skipVectorization,
        replacementSubStatus = task.subStatus,
        replacementSourceMimeType = task.sourceMimeType,
        replacementContentTruncated = task.contentTruncated,
        replacementTargetContentHash = task.targetContentHash,
        replacementTargetEpoch = task.targetEpoch,
        replacementCreatedAt = task.createdAt,
        replacementUpdatedAt = task.updatedAt,
    )

    @Transaction
    suspend fun upsertTarget(task: VectorizationTaskEntity): VectorizationTaskTargetUpsertResult {
        require(task.type == DOCUMENT_REFERENCE_TYPE) {
            "target upsert 仅支持 document_reference"
        }
        require(!task.workspaceRootUuid.isNullOrBlank() && !task.docId.isNullOrBlank()) {
            "document_reference workspace/doc 标识不能为空"
        }
        require(!task.targetContentHash.isNullOrBlank()) {
            "document_reference target_content_hash 不能为空"
        }
        require(task.targetEpoch > 0) {
            "document_reference target_epoch 必须大于 0"
        }

        repeat(MAX_TARGET_UPSERT_ATTEMPTS) {
            if (insertIgnore(task) != INSERT_CONFLICT) {
                return VectorizationTaskTargetUpsertResult(
                    VectorizationTaskTargetUpsertOutcome.INSERTED,
                    task.id,
                )
            }
            getById(task.id)?.let { idOwner ->
                if (idOwner.workspaceRootUuid != task.workspaceRootUuid ||
                    idOwner.docId != task.docId ||
                    idOwner.type != task.type
                ) {
                    throw IllegalStateException(
                        "vectorization task id 已属于另一 target: ${task.id}",
                    )
                }
            }
            val current = getByWorkspaceFile(task.workspaceRootUuid.orEmpty(), task.docId.orEmpty(), task.type)
                ?: throw IllegalStateException("vectorization task id 已被其他目标占用: ${task.id}")
            when {
                task.targetEpoch < current.targetEpoch ->
                    return VectorizationTaskTargetUpsertResult(
                        VectorizationTaskTargetUpsertOutcome.IGNORED_OLDER,
                        current.id,
                    )
                task.targetEpoch == current.targetEpoch && task.targetContentHash == current.targetContentHash ->
                    return VectorizationTaskTargetUpsertResult(
                        VectorizationTaskTargetUpsertOutcome.IDEMPOTENT,
                        current.id,
                    )
                task.targetEpoch == current.targetEpoch ->
                    throw IllegalStateException(
                        "同一 target_epoch 对应不同 content hash: ${task.targetEpoch}",
                    )
                replaceTargetIfExpected(current, task) == 1 ->
                    return VectorizationTaskTargetUpsertResult(
                        VectorizationTaskTargetUpsertOutcome.REPLACED_NEWER,
                        task.id,
                    )
            }
        }
        throw IllegalStateException("vectorization target 在并发更新后仍无法稳定写入")
    }

    @Query("""
        SELECT * FROM vectorization_tasks
        WHERE status IN ('pending', 'interrupted')
          AND (
            doc_id IS NULL OR workspace_root_uuid IS NULL OR EXISTS (
              SELECT 1 FROM workspace_files AS f
              WHERE f.workspace_root_uuid = vectorization_tasks.workspace_root_uuid
                AND f.uuid = vectorization_tasks.doc_id
                AND f.in_recycle_bin = 0
            )
          )
        ORDER BY created_at ASC
    """)
    suspend fun getRecoverableTasks(): List<VectorizationTaskEntity>

    @Query("""
        SELECT * FROM vectorization_tasks
        WHERE type IN ('document_reference', 'document') AND status IN ('failed', 'partial')
          AND (
            doc_id IS NULL OR workspace_root_uuid IS NULL OR EXISTS (
              SELECT 1 FROM workspace_files AS f
              WHERE f.workspace_root_uuid = vectorization_tasks.workspace_root_uuid
                AND f.uuid = vectorization_tasks.doc_id
                AND f.in_recycle_bin = 0
            )
          )
        ORDER BY updated_at DESC
    """)
    suspend fun getAttentionTasks(): List<VectorizationTaskEntity>

    @Query("""
        SELECT * FROM vectorization_tasks
        WHERE type = 'document_reference' AND status = 'completed'
    """)
    suspend fun getCompletedDocumentReferenceTasks(): List<VectorizationTaskEntity>

    @Query("""
        UPDATE vectorization_tasks
        SET status = 'interrupted'
        WHERE status IN ('processing', 'extracting_source', 'chunking', 'vectorizing', 'saving', 'extracting')
          AND updated_at < :staleThreshold
    """)
    suspend fun markStaleAsInterrupted(staleThreshold: Long)

    @Query("""
        UPDATE vectorization_tasks
        SET status = 'interrupted', updated_at = :updatedAt
        WHERE status IN ('processing', 'extracting_source', 'chunking', 'vectorizing', 'saving', 'extracting')
    """)
    suspend fun markProcessingAsInterrupted(updatedAt: Long): Int

    @Query("""
        DELETE FROM vectorization_tasks
        WHERE status = 'completed' OR (status = 'failed' AND type = 'memory')
    """)
    suspend fun deleteCompletedTasks()

    @Query("""
        DELETE FROM vectorization_tasks
        WHERE type != 'document_reference'
          AND (status = 'completed' OR (status = 'failed' AND type = 'memory'))
    """)
    suspend fun deleteCompletedNonReferenceTasks()

    @Query("DELETE FROM vectorization_tasks WHERE workspace_root_uuid = :workspaceRootUuid AND doc_id = :docId")
    suspend fun deleteByWorkspaceFile(workspaceRootUuid: String, docId: String)

    @Query("DELETE FROM vectorization_tasks WHERE workspace_root_uuid = :workspaceRootUuid AND doc_id IN (:docIds)")
    suspend fun deleteByWorkspaceFiles(workspaceRootUuid: String, docIds: List<String>): Int

    @Transaction
    suspend fun migrateLegacyToTarget(
        legacyTaskId: String,
        replacement: VectorizationTaskEntity,
    ): VectorizationTaskTargetUpsertResult? {
        val legacy = getById(legacyTaskId) ?: return null
        if (legacy.type != "document" || legacy.workspaceRootUuid != replacement.workspaceRootUuid ||
            legacy.docId != replacement.docId || replacement.type != DOCUMENT_REFERENCE_TYPE
        ) return null
        val upsert = upsertTarget(replacement)
        checkNotNull(getById(upsert.activeTaskId)) { "legacy 迁移后 active target 不存在" }
        delete(legacy)
        return upsert
    }

    private companion object {
        const val DOCUMENT_REFERENCE_TYPE = "document_reference"
        const val INSERT_CONFLICT = -1L
        const val MAX_TARGET_UPSERT_ATTEMPTS = 8
    }
}

data class VectorizationTaskTargetUpsertResult(
    val outcome: VectorizationTaskTargetUpsertOutcome,
    val activeTaskId: String,
)

enum class VectorizationTaskTargetUpsertOutcome {
    INSERTED,
    REPLACED_NEWER,
    IDEMPOTENT,
    IGNORED_OLDER,
}
