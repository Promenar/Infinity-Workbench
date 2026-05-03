package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.model.InferenceParams
import com.promenar.nexara.data.model.LoopStatus
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.SessionOptions
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.ui.chat.ChatStore

class SessionManager(
    private val store: ChatStore,
    private val sessionRepository: ISessionRepository
) {
    suspend fun addSession(session: Session) {
        val enrichedSession = session.copy(
            executionMode = session.executionMode.ifEmpty { "semi" },
            loopStatus = if (session.loopStatus == LoopStatus.IDLE) LoopStatus.COMPLETED else session.loopStatus,
        )

        try {
            sessionRepository.create(enrichedSession)
        } catch (_: Exception) {
        }

        store.update { state ->
            state.copy(sessions = listOf(enrichedSession) + state.sessions)
        }
    }

    suspend fun updateSession(id: String, updates: Map<String, Any?>) {
        try {
            sessionRepository.updatePartial(id, updates)
        } catch (_: Exception) {
        }

        store.updateSession(id) { s -> applyUpdatesToSession(s, updates) }
    }

    suspend fun deleteSession(id: String) {
        try {
            sessionRepository.delete(id)
        } catch (_: Exception) {
        }

        store.update { state ->
            state.copy(sessions = state.sessions.filter { it.id != id })
        }
    }

    fun getSession(id: String): Session? {
        return store.getSession(id)
    }

    suspend fun updateSessionDraft(sessionId: String, draft: String?) {
        updateSession(sessionId, mapOf("draft" to draft))
    }

    suspend fun toggleSessionPin(sessionId: String) {
        val session = store.getSession(sessionId) ?: return
        val newPinned = !session.isPinned
        updateSession(sessionId, mapOf("isPinned" to newPinned))
    }

    suspend fun updateSessionInferenceParams(id: String, params: InferenceParams) {
        updateSession(id, mapOf("inferenceParams" to params))
    }

    suspend fun updateSessionTitle(id: String, title: String) {
        updateSession(id, mapOf("title" to title))
    }

    suspend fun updateSessionPrompt(id: String, prompt: String?) {
        updateSession(id, mapOf("customPrompt" to prompt))
    }

    suspend fun updateSessionModel(id: String, modelId: String?) {
        val session = store.getSession(id)
        val toolsEnabled = modelId != null
        val newOptions = session?.options?.copy(toolsEnabled = toolsEnabled)
            ?: SessionOptions(toolsEnabled = toolsEnabled)
        updateSession(id, mapOf("modelId" to modelId, "options" to newOptions))
    }

    suspend fun updateSessionOptions(id: String, options: SessionOptions, ragOptions: RagOptions? = null) {
        val updates = mutableMapOf<String, Any?>("options" to options)
        if (ragOptions != null) {
            updates["ragOptions"] = ragOptions
        }
        updateSession(id, updates)
    }

    fun updateSessionScrollOffset(id: String, offset: Double) {
        store.updateSession(id) { s -> s.copy(scrollOffset = offset) }
    }

    fun getSessionsByAgent(agentId: String): List<Session> {
        return store.get().sessions
            .filter { it.agentId == agentId }
            .sortedWith(compareBy { if (it.isPinned) -1 else 1 })
    }

    suspend fun dismissActiveTask(sessionId: String) {
        updateSession(sessionId, mapOf("activeTask" to null))
    }

    fun setKGExtractionStatus(sessionId: String, isExtracting: Boolean) {
        store.update { state ->
            state.copy(
                activeKGExtractions = if (isExtracting) {
                    state.activeKGExtractions + sessionId
                } else {
                    state.activeKGExtractions - sessionId
                }
            )
        }
    }

    suspend fun toggleMcpServer(sessionId: String, serverId: String) {
        val session = store.getSession(sessionId) ?: return
        val current = session.activeMcpServerIds
        val next = if (current.contains(serverId)) {
            current.filter { it != serverId }
        } else {
            current + serverId
        }
        updateSession(sessionId, mapOf("activeMcpServerIds" to next))
    }

    suspend fun toggleSkill(sessionId: String, skillId: String) {
        val session = store.getSession(sessionId) ?: return
        val current = session.activeSkillIds
        val next = if (current.contains(skillId)) {
            current.filter { it != skillId }
        } else {
            current + skillId
        }
        updateSession(sessionId, mapOf("activeSkillIds" to next))
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyUpdatesToSession(session: Session, updates: Map<String, Any?>): Session {
        var result = session
        updates.forEach { (key, value) ->
            result = when (key) {
                "title" -> result.copy(title = value as? String ?: result.title)
                "lastMessage" -> result.copy(lastMessage = value as? String)
                "draft" -> result.copy(draft = value as? String)
                "isPinned" -> result.copy(isPinned = value as? Boolean ?: result.isPinned)
                "modelId" -> result.copy(modelId = value as? String)
                "customPrompt" -> result.copy(customPrompt = value as? String)
                "executionMode" -> result.copy(executionMode = value as? String ?: result.executionMode)
                "loopStatus" -> result.copy(loopStatus = value as? LoopStatus ?: result.loopStatus)
                "pendingIntervention" -> result.copy(pendingIntervention = value as? String)
                "approvalRequest" -> result.copy(approvalRequest = value as? com.promenar.nexara.data.model.ApprovalRequest)
                "inferenceParams" -> result.copy(inferenceParams = value as? InferenceParams)
                "activeTask" -> result.copy(activeTask = value as? com.promenar.nexara.data.model.TaskState)
                "stats" -> result.copy(stats = value as? com.promenar.nexara.data.model.SessionStats)
                "options" -> result.copy(options = value as? SessionOptions)
                "ragOptions" -> result.copy(ragOptions = value as? RagOptions)
                "activeMcpServerIds" -> result.copy(activeMcpServerIds = value as? List<String> ?: result.activeMcpServerIds)
                "activeSkillIds" -> result.copy(activeSkillIds = value as? List<String> ?: result.activeSkillIds)
                "workspacePath" -> result.copy(workspacePath = value as? String)
                else -> result
            }
        }
        return result
    }
}
