package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.model.Citation
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.RagMetadata
import com.promenar.nexara.data.model.RagProgress
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.TaskState
import com.promenar.nexara.data.model.TokenUsage
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.ToolResultArtifact
import com.promenar.nexara.data.model.json
import com.promenar.nexara.data.model.toDomain
import com.promenar.nexara.data.model.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.serializer
import java.util.UUID

open class MessageRepository(
    private val messageDao: MessageDao
) : IMessageRepository,
    com.promenar.nexara.domain.repository.IMessageRepository {

    private inline fun <reified T> encode(value: T): String =
        json.encodeToString(serializer<T>(), value)

    // ── Data-layer IMessageRepository (existing) ────────────────────────

    override suspend fun insert(message: Message, sessionId: String) {
        messageDao.insert(message.toEntity(sessionId))
    }

    open suspend fun update(message: Message, sessionId: String) {
        messageDao.update(message.toEntity(sessionId))
    }

    override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {
        val existing = messageDao.getById(messageId) ?: return
        val updated = applyPartialUpdates(existing, updates)
        messageDao.update(updated)
    }

    override suspend fun delete(messageId: String) {
        messageDao.deleteById(messageId)
    }

    override suspend fun deleteBySessionId(sessionId: String) {
        messageDao.deleteBySessionId(sessionId)
    }

    override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) {
        messageDao.deleteBySessionIdAndTimestampAfter(sessionId, timestamp)
    }

    override suspend fun getById(messageId: String): Message? {
        return messageDao.getById(messageId)?.toDomain()
    }

    override suspend fun getBySession(sessionId: String): List<Message> {
        return messageDao.getBySession(sessionId).map { it.toDomain() }
    }

    override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) {
        messageDao.updateVectorizationStatus(messageId, status, isArchived?.let { if (it) 1 else 0 })
    }

    private fun applyPartialUpdates(
        entity: MessageEntity,
        updates: Map<String, Any?>
    ): MessageEntity {
        var result = entity
        updates.forEach { (key, value) ->
            result = when (key) {
                "content" -> result.copy(content = value as? String ?: result.content)
                "status" -> result.copy(status = value as? String)
                "reasoning" -> result.copy(reasoning = value as? String)
                "thoughtSignature" -> result.copy(thoughtSignature = value as? String)
                "tokens" -> result.copy(tokens = value?.let { encode(it as TokenUsage) })
                "citations" -> result.copy(citations = value?.let { encode(it as List<Citation>) })
                "ragReferences" -> result.copy(ragReferences = value?.let { encode(it as List<RagReference>) })
                "ragProgress" -> result.copy(ragProgress = value?.let { encode(it as RagProgress) })
                "ragMetadata" -> result.copy(ragMetadata = value?.let { encode(it as RagMetadata) })
                "ragReferencesLoading" -> result.copy(ragReferencesLoading = if (value as? Boolean == true) 1 else 0)
                "executionSteps" -> result.copy(executionSteps = value?.let { encode(it as List<ExecutionStep>) })
                "toolCalls" -> result.copy(toolCalls = value?.let { encode(it as List<ToolCall>) })
                "pendingApprovalToolIds" -> result.copy(pendingApprovalToolIds = value?.let { encode(it as List<String>) })
                "planningTask" -> result.copy(planningTask = value?.let { encode(it as TaskState) })
                "isArchived" -> result.copy(isArchived = if (value as? Boolean == true) 1 else 0)
                "vectorizationStatus" -> result.copy(vectorizationStatus = value as? String)
                "layoutHeight" -> result.copy(layoutHeight = value as? Double)
                "toolResults" -> result.copy(toolResults = value?.let { encode(it as List<ToolResultArtifact>) })
                "isError" -> result.copy(isError = if (value as? Boolean == true) 1 else 0)
                "errorMessage" -> result.copy(errorMessage = value as? String)
                else -> result
            }
        }
        return result
    }

    // ── Domain-layer IMessageRepository ─────────────────────────────────

    override fun observeBySession(sessionId: String): Flow<List<com.promenar.nexara.domain.model.Message>> {
        return messageDao.observeBySession(sessionId).map { entities ->
            entities.map { it.toDomainMessage() }
        }
    }

    override suspend fun send(
        sessionId: String,
        content: String,
        role: com.promenar.nexara.domain.model.MessageRole
    ): com.promenar.nexara.domain.model.Message {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val dataMessage = Message(
            id = id,
            role = MessageRole.valueOf(role.name),
            content = content,
            createdAt = now
        )
        messageDao.insert(dataMessage.toEntity(sessionId))
        return dataMessage.toDomainMessage(sessionId)
    }

    override suspend fun appendContent(messageId: String, chunk: String) {
        val existing = messageDao.getById(messageId) ?: return
        messageDao.update(existing.copy(content = existing.content + chunk))
    }

    // ── Mappers ─────────────────────────────────────────────────────────

    private fun MessageEntity.toDomainMessage(): com.promenar.nexara.domain.model.Message =
        toDomain().toDomainMessage(sessionId)

    private fun Message.toDomainMessage(sessionId: String): com.promenar.nexara.domain.model.Message =
        com.promenar.nexara.domain.model.Message(
            id = id,
            sessionId = sessionId,
            role = com.promenar.nexara.domain.model.MessageRole.valueOf(role.name),
            content = content,
            thinking = reasoning,
            toolCalls = toolCalls?.map { it.toDomainToolCall() },
            ragReferences = ragReferences?.map { it.toDomainRagReference() },
            tokenUsage = tokens?.toDomainTokenUsage(),
            timestamp = createdAt
        )

    private fun ToolCall.toDomainToolCall(): com.promenar.nexara.domain.model.ToolCall =
        com.promenar.nexara.domain.model.ToolCall(
            id = id,
            name = name,
            arguments = arguments,
            result = null,
            status = com.promenar.nexara.domain.model.ToolCallStatus.COMPLETED
        )

    private fun RagReference.toDomainRagReference(): com.promenar.nexara.domain.model.RagReference =
        com.promenar.nexara.domain.model.RagReference(
            chunkId = id,
            documentTitle = source,
            snippet = content,
            score = score.toDouble()
        )

    private fun TokenUsage.toDomainTokenUsage(): com.promenar.nexara.domain.model.TokenUsage =
        com.promenar.nexara.domain.model.TokenUsage(
            input = input,
            output = output
        )
}
