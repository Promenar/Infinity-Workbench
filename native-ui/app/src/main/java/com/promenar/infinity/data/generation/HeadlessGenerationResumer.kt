package com.promenar.infinity.data.generation

import com.promenar.infinity.data.model.LoopStatus
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.contentForModel
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.domain.generation.GenerationCoordinator
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationRuntimePolicy
import com.promenar.infinity.domain.generation.GenerationTaskSnapshot
import com.promenar.infinity.domain.generation.StartGenerationResult
import com.promenar.infinity.domain.usecase.IdGenerator
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.MessageManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 应用级确认恢复只访问目标会话，与当前页面和其它会话的生成相互独立。 */
class HeadlessGenerationResumer(
    private val store: ChatStore,
    private val interactions: GenerationInteractionStore,
    private val coordinator: GenerationCoordinator,
    private val messages: MessageManager,
    private val awaitReady: suspend () -> Unit,
    private val loadSession: suspend (String) -> Session?,
    private val updateSession: suspend (String, Map<String, Any?>) -> Unit,
    private val confirmUnlimited: suspend () -> Unit,
    private val track: (GenerationTaskSnapshot) -> Unit,
    private val executionGate: SessionExecutionGate,
) {
    suspend fun resume(prompt: GenerationInteraction, allowed: Boolean) {
        try {
            awaitReady()
            coordinator.awaitSessionIdle(prompt.sessionId)
            store.turnAdmission.withLock(prompt.sessionId) {
                executionGate.withNewSessionAdmission(prompt.sessionId) {
                    resumeAdmitted(prompt, allowed)
                }
            }
        } catch (failure: Throwable) {
            interactions.retryHeadlessResponse(prompt.id)
            throw failure
        }
    }

    private suspend fun resumeAdmitted(prompt: GenerationInteraction, allowed: Boolean) {
        var assistantId: String? = null
        var acceptedTaskId: String? = null
        try {
            if (interactions.pendingBySession.value[prompt.sessionId]?.id != prompt.id) return
            if (!allowed) {
                updateSession(prompt.sessionId, mapOf("loopStatus" to LoopStatus.COMPLETED))
                interactions.clearHeadless(prompt.sessionId, prompt.id)
                return
            }
            check(!coordinator.isSessionBusy(prompt.sessionId)) { "目标会话正在生成" }
            var session = store.getOrLoadSession(prompt.sessionId) { loadSession(prompt.sessionId) }
            if (session?.ragOptions == null && session != null) {
                val options = loadSession(prompt.sessionId)?.ragOptions
                store.updateSession(prompt.sessionId) { it.copy(ragOptions = options) }
                session = store.getSession(prompt.sessionId)
            }
            if (session == null) {
                interactions.clearHeadless(prompt.sessionId, prompt.id)
                return
            }
            val user = checkNotNull(generationResumption(session)?.user) { "目标会话缺少可恢复的用户轮次" }
            if (prompt.kind == GenerationInteractionKind.UNLIMITED_AUTO) confirmUnlimited()
            val preparedAssistantId = IdGenerator.message("ai")
            assistantId = preparedAssistantId
            messages.addMessage(prompt.sessionId, Message(
                id = preparedAssistantId, role = MessageRole.ASSISTANT, content = "",
                modelId = session.modelId, parentMessageId = user.id,
            ))
            val start = coordinator.start(GenerationRequest(
                sessionId = session.id, assistantMessageId = preparedAssistantId, userMessageId = user.id,
                userContent = user.contentForModel(), imageDataUrls = user.userImages.orEmpty(),
                runtimePolicy = GenerationRuntimePolicy.BACKGROUND_ALLOWED,
                requestId = prompt.id,
            )) { taskId ->
                interactions.consumeHeadlessResponse(prompt).also { consumed ->
                    if (consumed) acceptedTaskId = taskId
                }
            }
            val taskId = when (start) {
                is StartGenerationResult.Started -> start.taskId
                is StartGenerationResult.Queued -> start.taskId
                is StartGenerationResult.Existing -> start.snapshot.taskId
                is StartGenerationResult.Busy, is StartGenerationResult.Rejected -> error("生成器未接受恢复请求")
            }
            acceptedTaskId = taskId
            if (start is StartGenerationResult.Existing && start.snapshot.assistantMessageId != preparedAssistantId) {
                messages.discardPreparedMessages(session.id, listOf(preparedAssistantId))
            }
            interactions.clearHeadless(prompt.sessionId, prompt.id)
            coordinator.observe(session.id).value?.takeIf { it.taskId == taskId }?.let(track)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    if (acceptedTaskId == null) {
                        assistantId?.let { messages.discardPreparedMessages(prompt.sessionId, listOf(it)) }
                    } else {
                        // 已受理后回执、持久化或前台跟踪失败不能撤销占位，也不能重新开放旧确认。
                        interactions.clearHeadless(prompt.sessionId, prompt.id)
                        coordinator.observe(prompt.sessionId).value?.takeIf { it.taskId == acceptedTaskId }?.let(track)
                    }
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }
}
