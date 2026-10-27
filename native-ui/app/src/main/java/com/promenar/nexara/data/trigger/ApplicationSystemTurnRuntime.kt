package com.promenar.nexara.data.trigger

import com.promenar.nexara.data.backup.BackupStartupState
import com.promenar.nexara.data.generation.GenerationInteractionStore
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.session.SessionExecutionGate
import com.promenar.nexara.data.session.SessionDeletingException
import com.promenar.nexara.domain.generation.*
import com.promenar.nexara.domain.trigger.*
import com.promenar.nexara.background.generation.GenerationForegroundController
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.MessageManager
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 与页面共用应用级生成器、消息管理器及会话载入锁。 */
class ApplicationSystemTurnRuntime(
    private val startup: StateFlow<BackupStartupState>,
    private val store: ChatStore,
    private val findSession: suspend (String) -> Session?,
    private val exists: suspend (String) -> Boolean,
    private val hasApproval: suspend (String) -> Boolean,
    private val messages: MessageManager,
    private val coordinator: GenerationCoordinator,
    private val foreground: GenerationForegroundController,
    private val interactions: GenerationInteractionStore,
    private val executionGate: SessionExecutionGate,
    private val notify: suspend (SystemTurnRequest, SystemTurnOutcome, String?) -> Unit,
    private val failureText: (GenerationError?) -> String,
) : SystemTurnRuntime {
    override suspend fun awaitReady() { startup.first { it == BackupStartupState.Ready } }
    override suspend fun sessionExists(sessionId: String) = withContext(Dispatchers.IO) { exists(sessionId) }
    override suspend fun loadSession(sessionId: String) = withContext(Dispatchers.IO) {
        val session = store.getOrLoadSession(sessionId) { findSession(sessionId) } ?: return@withContext false
        if (session.ragOptions == null) {
            val options = findSession(sessionId)?.ragOptions
            store.updateSession(sessionId) { it.copy(ragOptions = options) }
        }
        true
    }
    override suspend fun isBusy(sessionId: String): Boolean =
        coordinator.isSessionBusy(sessionId) ||
            interactions.pendingBySession.value.containsKey(sessionId) || withContext(Dispatchers.IO) { hasApproval(sessionId) }

    override suspend fun run(request: SystemTurnRequest, ticket: SystemTurnAdmissionTicket): SystemTurnOutcome? = try {
        runTurn(request, ticket)
    } catch (cancelled: CancellationException) {
        ticket.acceptedTaskId?.let { taskId ->
            coordinator.cancel(taskId, CancellationReason.TIMEOUT)
            withContext(NonCancellable) { coordinator.awaitTaskCompletion(request.sessionId, taskId) }
        }
        throw cancelled
    } catch (_: Exception) {
        ticket.acceptedTaskId?.let { taskId ->
            coordinator.cancel(taskId, CancellationReason.SHUTDOWN)
            withContext(NonCancellable) { coordinator.awaitTaskCompletion(request.sessionId, taskId) }
        }
        SystemTurnOutcome.Failed(failureText(null)).also { notify(request, it, null) }
    }

    private suspend fun runTurn(request: SystemTurnRequest, ticket: SystemTurnAdmissionTicket): SystemTurnOutcome? {
        var startedTaskId: String? = null
        var immediate: SystemTurnOutcome? = null
        var busy = false
        try {
            store.turnAdmission.withLock(request.sessionId) {
                executionGate.withNewSessionAdmission(request.sessionId) {
                    if (!sessionExists(request.sessionId)) {
                        immediate = SystemTurnOutcome.Skipped(SystemTurnSkipReason.SESSION_MISSING)
                        return@withNewSessionAdmission
                    }
                    if (isBusy(request.sessionId)) { busy = true; return@withNewSessionAdmission }
                    val session = store.getSession(request.sessionId) ?: run { busy = true; return@withNewSessionAdmission }
                    val text = SystemTriggerMessage.compose(request, OffsetDateTime.now())
                    val userId = "user_${UUID.randomUUID()}"
                    val assistantId = "ai_${UUID.randomUUID()}"
                    val prepared = listOf(userId, assistantId)
                    try {
                        messages.addMessage(request.sessionId, Message(
                            id = userId, role = MessageRole.USER, content = text,
                            name = SystemTriggerMessage.MESSAGE_NAME,
                        ))
                        messages.addMessage(request.sessionId, Message(
                            id = assistantId, role = MessageRole.ASSISTANT, content = "",
                            modelId = session.modelId, parentMessageId = userId,
                        ))
                        when (val start = coordinator.start(GenerationRequest(
                            sessionId = request.sessionId, assistantMessageId = assistantId,
                            userMessageId = userId, userContent = text, imageDataUrls = emptyList(),
                            runtimePolicy = GenerationRuntimePolicy.BACKGROUND_ALLOWED,
                            interactionMode = GenerationInteractionMode.HEADLESS,
                        ), ticket::tryAccept)) {
                            is StartGenerationResult.Started -> {
                                startedTaskId = start.taskId
                            }
                            is StartGenerationResult.Queued -> {
                                startedTaskId = start.taskId
                            }
                            is StartGenerationResult.Existing -> {
                                startedTaskId = start.snapshot.taskId
                            }
                            is StartGenerationResult.Busy -> {
                                messages.discardPreparedMessages(request.sessionId, prepared)
                                busy = true
                                return@withNewSessionAdmission
                            }
                            is StartGenerationResult.Rejected -> {
                                messages.discardPreparedMessages(request.sessionId, prepared)
                                val result = SystemTurnOutcome.Failed(failureText(start.error))
                                notify(request, result, null)
                                immediate = result
                                return@withNewSessionAdmission
                            }
                        }
                    } catch (failure: Throwable) {
                        if (ticket.acceptedTaskId == null) withContext(NonCancellable) {
                            messages.discardPreparedMessages(request.sessionId, prepared)
                        }
                        throw failure
                    }
                }
            }
        } catch (_: SessionDeletingException) {
            return SystemTurnOutcome.Skipped(SystemTurnSkipReason.SESSION_MISSING)
        }
        if (busy) return null
        immediate?.let { return it }
        val taskId = checkNotNull(startedTaskId)
        coordinator.observe(request.sessionId).value?.takeIf { it.taskId == taskId }?.let {
            // 平台拒绝前台服务时仍由应用作用域继续；控制器把拒绝转换为结果。
            foreground.track(it)
        }
        val terminal = coordinator.awaitTaskCompletion(request.sessionId, taskId)
        val outcome = when {
            terminal?.phase == GenerationPhase.WAITING_APPROVAL || terminal?.phase == GenerationPhase.WAITING_INPUT -> SystemTurnOutcome.NeedsUser
            terminal?.phase == GenerationPhase.COMPLETED -> SystemTurnOutcome.Completed
            else -> SystemTurnOutcome.Failed(failureText(terminal?.error))
        }
        if (terminal?.phase != GenerationPhase.PERSISTENCE_FAILED) coordinator.acknowledgeTerminal(taskId)
        coordinator.release(request.sessionId, taskId)
        val reply = store.getSession(request.sessionId)?.messages?.firstOrNull { it.id == terminal?.assistantMessageId }?.content
        notify(request, outcome, reply)
        return outcome
    }

}
