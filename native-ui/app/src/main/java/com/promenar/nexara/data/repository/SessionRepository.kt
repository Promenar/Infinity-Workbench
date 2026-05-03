package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.data.model.InferenceParams
import com.promenar.nexara.data.model.LoopStatus
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.SessionOptions
import com.promenar.nexara.data.model.SessionStats
import com.promenar.nexara.data.model.TaskState
import com.promenar.nexara.data.model.json
import com.promenar.nexara.data.model.toDomain
import com.promenar.nexara.data.model.toEntity
import kotlinx.serialization.serializer

open class SessionRepository(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao
) : ISessionRepository {
    private inline fun <reified T> encode(value: T): String =
        json.encodeToString(serializer<T>(), value)

    override suspend fun create(session: Session) {
        sessionDao.insert(session.toEntity())
    }

    open suspend fun update(session: Session) {
        sessionDao.update(session.toEntity())
    }

    override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {
        val now = System.currentTimeMillis()
        val existing = sessionDao.getById(id) ?: return
        val updated = applyPartialUpdates(existing.copy(updatedAt = now), updates)
        sessionDao.update(updated)
    }

    override suspend fun delete(id: String) {
        messageDao.deleteBySessionId(id)
        sessionDao.deleteById(id)
    }

    override suspend fun getById(id: String): Session? {
        val entity = sessionDao.getById(id) ?: return null
        val messages = messageDao.getBySession(id).map { it.toDomain() }
        return entity.toDomain().copy(messages = messages)
    }

    override suspend fun getAll(): List<Session> {
        return sessionDao.getAll().map { entity ->
            val messages = messageDao.getBySession(entity.id).map { it.toDomain() }
            entity.toDomain().copy(messages = messages)
        }
    }

    private fun applyPartialUpdates(
        entity: SessionEntity,
        updates: Map<String, Any?>
    ): SessionEntity {
        var result = entity
        updates.forEach { (key, value) ->
            result = when (key) {
                "title" -> result.copy(title = value as? String ?: result.title)
                "lastMessage" -> result.copy(lastMessage = value as? String)
                "draft" -> result.copy(draft = value as? String)
                "isPinned" -> result.copy(isPinned = if (value as? Boolean == true) 1 else 0)
                "modelId" -> result.copy(modelId = value as? String)
                "customPrompt" -> result.copy(customPrompt = value as? String)
                "executionMode" -> result.copy(executionMode = value as? String ?: result.executionMode)
                "loopStatus" -> result.copy(loopStatus = (value as? LoopStatus)?.toSerializedName() ?: result.loopStatus)
                "pendingIntervention" -> result.copy(pendingIntervention = value as? String)
                "approvalRequest" -> result.copy(approvalRequest = value?.let { encode(it as ApprovalRequest) })
                "inferenceParams" -> result.copy(inferenceParams = value?.let { encode(it as InferenceParams) })
                "activeTask" -> result.copy(activeTask = value?.let { encode(it as TaskState) })
                "stats" -> result.copy(stats = value?.let { encode(it as SessionStats) })
                "options" -> result.copy(options = value?.let { encode(it as SessionOptions) })
                "ragOptions" -> result.copy(ragOptions = value?.let { encode(it as RagOptions) })
                "activeMcpServerIds" -> result.copy(activeMcpServerIds = value?.let { encode(it as List<String>) })
                "activeSkillIds" -> result.copy(activeSkillIds = value?.let { encode(it as List<String>) })
                "workspacePath" -> result.copy(workspacePath = value as? String)
                else -> result
            }
        }
        return result
    }
}
