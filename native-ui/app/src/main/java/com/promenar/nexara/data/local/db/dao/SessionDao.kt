package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.promenar.nexara.data.local.db.entity.ContextSummaryEntity
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.model.ContextCompactionSource
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.json
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: String)

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    fun observeById(sessionId: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY updated_at DESC")
    suspend fun getAll(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE is_pinned = 1 ORDER BY updated_at DESC")
    fun observePinned(): Flow<List<SessionEntity>>

    @Query("UPDATE sessions SET title = :title, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateTitle(sessionId: String, title: String, updatedAt: Long)

    @Query("UPDATE sessions SET last_message = :lastMessage, time = :time, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateLastMessage(sessionId: String, lastMessage: String?, time: String?, updatedAt: Long)

    @Query("UPDATE sessions SET draft = :draft, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateDraft(sessionId: String, draft: String?, updatedAt: Long)

    @Query("UPDATE sessions SET is_pinned = :isPinned, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updatePinned(sessionId: String, isPinned: Int, updatedAt: Long)

    @Query("UPDATE sessions SET inference_params = :inferenceParams, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateInferenceParams(sessionId: String, inferenceParams: String?, updatedAt: Long)

    @Query("UPDATE sessions SET model_id = :modelId, options = :options, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateModel(sessionId: String, modelId: String?, options: String?, updatedAt: Long)

    @Query("UPDATE sessions SET active_mcp_server_ids = :activeMcpServerIds, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateActiveMcpServerIds(sessionId: String, activeMcpServerIds: String?, updatedAt: Long)

    @Query("UPDATE sessions SET active_skill_ids = :activeSkillIds, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateActiveSkillIds(sessionId: String, activeSkillIds: String?, updatedAt: Long)

    @Query("UPDATE sessions SET active_task = :activeTask, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateActiveTask(sessionId: String, activeTask: String?, updatedAt: Long)

    @Query(
        """UPDATE sessions
           SET approval_request = :approvalRequest,
               loop_status = :loopStatus,
               updated_at = :updatedAt
           WHERE id = :sessionId""",
    )
    suspend fun updateToolApprovalState(
        sessionId: String,
        approvalRequest: String?,
        loopStatus: String,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM sessions WHERE approval_request IS NOT NULL OR loop_status = 'waiting_for_approval'")
    suspend fun getWithToolApprovalState(): List<SessionEntity>

    /** 系统触发排队时轮询，只判断是否存在待批准的工具调用。 */
    @Query("SELECT EXISTS(SELECT 1 FROM sessions WHERE approval_request IS NOT NULL AND approval_request != '')")
    suspend fun hasPendingApproval(): Boolean

    @Query("UPDATE sessions SET options = :options, rag_options = :ragOptions, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateOptions(sessionId: String, options: String?, ragOptions: String?, updatedAt: Long)

    @Query(
        """SELECT summary.* FROM context_summaries AS summary
           JOIN messages AS start_message
             ON start_message.id = summary.start_message_id
            AND start_message.session_id = summary.session_id
           JOIN messages AS end_message
             ON end_message.id = summary.end_message_id
            AND end_message.session_id = summary.session_id
           WHERE summary.session_id = :sessionId
             AND (start_message.created_at < end_message.created_at
                  OR (start_message.created_at = end_message.created_at
                      AND start_message.rowid <= end_message.rowid))
           ORDER BY summary.created_at DESC, summary.rowid DESC LIMIT 1""",
    )
    suspend fun getCurrentContextSummary(sessionId: String): ContextSummaryEntity?

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY created_at ASC, rowid ASC")
    suspend fun getContextMessages(sessionId: String): List<MessageEntity>

    @Transaction
    suspend fun getContextSnapshot(sessionId: String): SessionContextSnapshot? {
        val session = getById(sessionId) ?: return null
        val messages = getContextMessages(sessionId)
        val summary = getCurrentContextSummary(sessionId)?.takeIf { it.summaryContent.isNotBlank() }
        if (summary == null) {
            val archivedCount = messages.count { it.isArchived != 0 }
            if (archivedCount > 0) {
                check(unarchiveContextMessages(sessionId) == archivedCount) { "缺失摘要的历史恢复数量不一致" }
            }
            deleteContextSummaries(sessionId)
            return SessionContextSnapshot(session, messages.map { it.copy(isArchived = 0) }, null)
        }
        return SessionContextSnapshot(session, messages, summary)
    }

    @Query("SELECT id FROM messages WHERE session_id = :sessionId ORDER BY created_at ASC, rowid ASC")
    suspend fun getContextMessageIds(sessionId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContextSummary(summary: ContextSummaryEntity)

    @Query("DELETE FROM context_summaries WHERE session_id = :sessionId")
    suspend fun deleteContextSummaries(sessionId: String)

    @Query("UPDATE messages SET is_archived = 1 WHERE session_id = :sessionId AND id IN (:messageIds)")
    suspend fun archiveContextMessages(sessionId: String, messageIds: List<String>): Int

    @Query("UPDATE messages SET is_archived = 0 WHERE session_id = :sessionId AND is_archived != 0")
    suspend fun unarchiveContextMessages(sessionId: String): Int

    @Transaction
    suspend fun commitContextCompaction(
        sessionId: String,
        summary: String,
        messageIds: List<String>,
        source: ContextCompactionSource,
    ) {
        val ids = messageIds.toList()
        val sourceMessages = source.messages.toList()
        require(summary.isNotBlank()) { "上下文摘要不能为空" }
        require(ids.isNotEmpty() && ids.size == ids.toSet().size) {
            "摘要消息清单不能为空或包含重复项"
        }
        require(sourceMessages.map { it.id } == ids) { "摘要输入快照与消息清单不一致" }
        checkNotNull(getById(sessionId)) { "摘要所属会话不存在" }
        val messages = getContextMessages(sessionId)
        val orderedIds = messages.map { it.id }
        require(orderedIds.toSet().containsAll(ids)) { "摘要消息缺失或不属于当前会话" }
        val previous = getCurrentContextSummary(sessionId)?.takeIf { it.summaryContent.isNotBlank() }
        check(previous?.summaryContent == source.previousSummary) { "历史摘要已变化，请重新压缩" }
        val messagesById = messages.associateBy { it.id }
        check(sourceMessages.all { expected -> messagesById.getValue(expected.id).matchesContextSource(expected) }) {
            "摘要消息内容或关系已变化，请重新压缩"
        }
        val coveredIds = ids.toSet() + listOfNotNull(previous?.startMessageId, previous?.endMessageId)
        val span = orderedIds.filter { it in coveredIds }
        deleteContextSummaries(sessionId)
        insertContextSummary(ContextSummaryEntity(
            id = previous?.id ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            startMessageId = span.first(),
            endMessageId = span.last(),
            summaryContent = summary,
            createdAt = System.currentTimeMillis(),
        ))
        // 为完整长历史分块绑定参数；任一分块失败都会回滚摘要和此前归档。
        ids.chunked(900).forEach { chunk ->
            check(archiveContextMessages(sessionId, chunk) == chunk.size) { "摘要消息归档数量不一致" }
        }
    }

    @Transaction
    suspend fun updateWithContextSummary(session: SessionEntity, summary: String?) {
        checkNotNull(getById(session.id)) { "摘要所属会话不存在" }
        if (summary == null) {
            deleteContextSummaries(session.id)
        } else {
            require(summary.isNotBlank()) { "上下文摘要不能为空" }
            val orderedIds = getContextMessageIds(session.id)
            check(orderedIds.isNotEmpty()) { "上下文摘要缺少有效消息跨度" }
            val previous = getCurrentContextSummary(session.id)?.takeIf { it.summaryContent.isNotBlank() }
            deleteContextSummaries(session.id)
            insertContextSummary(ContextSummaryEntity(
                id = previous?.id ?: UUID.randomUUID().toString(),
                sessionId = session.id,
                startMessageId = previous?.startMessageId ?: orderedIds.first(),
                endMessageId = previous?.endMessageId ?: orderedIds.last(),
                summaryContent = summary,
                createdAt = System.currentTimeMillis(),
            ))
        }
        update(session)
    }
}

data class SessionContextSnapshot(
    val session: SessionEntity,
    val messages: List<MessageEntity>,
    val summary: ContextSummaryEntity?,
)

private fun MessageEntity.matchesContextSource(expected: Message): Boolean =
    content == expected.content &&
        MessageRole.fromSerializedName(role) == expected.role &&
        (isArchived == 1) == expected.isArchived &&
        toolCallId == expected.toolCallId &&
        name == expected.name &&
        parentMessageId == expected.parentMessageId &&
        toolCalls?.let { json.decodeFromString<List<ToolCall>>(it) } == expected.toolCalls
