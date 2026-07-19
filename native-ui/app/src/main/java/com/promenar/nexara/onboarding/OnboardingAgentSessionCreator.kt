package com.promenar.nexara.onboarding

import com.promenar.nexara.data.model.LoopStatus
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.ui.chat.ChatStore

data class OnboardingAgentSession(
    val agentId: String,
    val sessionId: String,
)

/** 创建首次引导 Agent 与 Session；任一后续步骤失败时按相反顺序清理持久层和内存态。 */
internal suspend fun createOnboardingAgentSession(
    modelId: String,
    createAgent: suspend () -> String,
    newSessionId: () -> String,
    createSession: suspend (Session) -> Unit,
    deleteSession: suspend (String) -> Unit,
    deleteAgent: suspend (String) -> Unit,
    chatStore: ChatStore,
    recordCheckpoint: (String, String) -> Boolean,
): OnboardingAgentSession {
    var agentId: String? = null
    val sessionId = newSessionId()
    var sessionCreationAttempted = false
    try {
        val createdAgentId = createAgent()
        agentId = createdAgentId
        val session = Session(
            id = sessionId,
            agentId = createdAgentId,
            modelId = modelId,
            executionMode = "semi",
            loopStatus = LoopStatus.COMPLETED,
        )
        sessionCreationAttempted = true
        createSession(session)
        chatStore.upsertSession(session)
        check(recordCheckpoint(createdAgentId, sessionId)) { "首次引导检查点写入失败" }
        return OnboardingAgentSession(createdAgentId, sessionId)
    } catch (error: Throwable) {
        chatStore.removeSession(sessionId)
        if (sessionCreationAttempted) runCatching { deleteSession(sessionId) }
        agentId?.let { createdAgentId -> runCatching { deleteAgent(createdAgentId) } }
        throw error
    }
}
