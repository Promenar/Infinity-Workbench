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
import com.promenar.nexara.utils.NexaraLogger
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

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
    private val subagentCompletions: SubagentCompletionSteering? = null,
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

    override fun stageSubagentCompletion(request: SystemTurnRequest, ticket: SystemTurnAdmissionTicket) {
        subagentCompletions?.enqueue(request, ticket)
    }

    override fun discardSubagentCompletion(ticket: SystemTurnAdmissionTicket) {
        subagentCompletions?.remove(ticket)
    }

    /** 只恢复已送达但尚无执行证据的末尾通知，不重新插入完成消息。 */
    suspend fun recoverSubagentCompletions(sessionIds: List<String>) {
        val bridge = subagentCompletions ?: return
        awaitReady()
        for (sessionId in sessionIds.distinct()) {
            try {
                store.turnAdmission.withLock(sessionId) {
                    executionGate.withNewSessionAdmission(sessionId) {
                        if (!sessionExists(sessionId)) return@withNewSessionAdmission
                        val phase = coordinator.observe(sessionId).value?.phase
                        if (phase == GenerationPhase.WAITING_INPUT || phase == GenerationPhase.WAITING_APPROVAL || isBusy(sessionId)) {
                            return@withNewSessionAdmission
                        }
                        val session = store.getSession(sessionId) ?: withContext(Dispatchers.IO) { findSession(sessionId) }
                            ?: return@withNewSessionAdmission
                        // 普通历史只临时读取；需要恢复的会话才进入应用内存。
                        if (pendingCompletion(session) == null) return@withNewSessionAdmission
                        val loaded = store.getOrLoadSession(sessionId) { session } ?: return@withNewSessionAdmission
                        val pending = pendingCompletion(loaded) ?: return@withNewSessionAdmission
                        startCompletionTurn(pending.delivered, bridge, pending.assistantId, pending.preparedUser)
                    }
                }
            } catch (_: SessionDeletingException) {
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                NexaraLogger.logError("SubagentCompletionRecovery", failure)
            }
        }
    }

    private data class PendingCompletion(
        val delivered: SubagentCompletionSteering.Delivered,
        val assistantId: String?,
        val preparedUser: Message? = null,
    )

    private fun pendingCompletion(session: Session): PendingCompletion? {
        val last = session.messages.lastOrNull() ?: return null
        val placeholder = last.takeIf { it.role == MessageRole.ASSISTANT && it.isUnstartedCompletionPlaceholder() }
        val user = if (placeholder == null) last else session.messages.getOrNull(session.messages.lastIndex - 1)
        if (user == null || user.role != MessageRole.USER || user.name != SystemTriggerMessage.MESSAGE_NAME) return null
        if (placeholder != null && placeholder.parentMessageId != user.id) return null
        val parsed = SystemTriggerMessage.parse(user.content)?.takeIf { it.kind == SystemTurnKind.SUBAGENT_DONE } ?: return null
        val consumer = parsed.consumerAssistantId?.let { id ->
            session.messages.firstOrNull { it.id == id && it.role == MessageRole.ASSISTANT }
        }
        if (consumer != null && !consumer.isUnstartedCompletionPlaceholder()) return null
        val preparedUser = if (placeholder == null && consumer != null) {
            session.messages.takeWhile { it.id != consumer.id }
                .firstOrNull { it.id == consumer.parentMessageId && it.role == MessageRole.USER }
        } else null
        val request = SystemTurnRequest(
            sessionId = session.id, kind = parsed.kind, sourceId = parsed.sourceId,
            title = parsed.title, body = parsed.body, sequence = parsed.sequence,
            catchUp = parsed.catchUp, deliveryIds = parsed.deliveryIds,
            consumerAssistantId = parsed.consumerAssistantId,
        )
        val assistantId = placeholder?.id ?: consumer?.id?.takeIf { preparedUser != null }
        return PendingCompletion(SubagentCompletionSteering.Delivered(request, user.id, user.content), assistantId, preparedUser)
    }

    override suspend fun runSubagentCompletion(request: SystemTurnRequest, ticket: SystemTurnAdmissionTicket): SystemTurnOutcome? {
        val bridge = subagentCompletions ?: return if (isBusy(request.sessionId)) null else run(request, ticket)
        val pending = bridge.enqueue(request, ticket)
        try {
            if (!loadSession(request.sessionId)) return SystemTurnOutcome.Skipped(SystemTurnSkipReason.SESSION_MISSING)
            // 同一批已入队通知先全部进入桥，再由同一个步骤边界合并消费。
            yield()
            var retryMillis = 250L
            while (!pending.outcome.isCompleted && !ticket.isWithdrawn) {
                // 接受票据只代表正在写入，持久化回执才代表已经送达。
                if (ticket.acceptedTaskId != null) return pending.outcome.await()
                store.turnAdmission.withLock(request.sessionId) {
                    executionGate.withNewSessionAdmission(request.sessionId) {
                        if (pending.outcome.isCompleted || !ticket.isOpen) return@withNewSessionAdmission
                        if (!sessionExists(request.sessionId)) {
                            pending.outcome.complete(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SESSION_MISSING))
                            return@withNewSessionAdmission
                        }
                        val phase = coordinator.observe(request.sessionId).value?.phase
                        if (phase == GenerationPhase.WAITING_INPUT || phase == GenerationPhase.WAITING_APPROVAL || isBusy(request.sessionId)) {
                            return@withNewSessionAdmission
                        }
                        bridge.deliver(request.sessionId, store, messages)?.let { delivered ->
                            startCompletionTurn(delivered, bridge)
                        }
                    }
                }
                withTimeoutOrNull(retryMillis) { pending.outcome.await() }?.let { return it }
                retryMillis = (retryMillis * 2).coerceAtMost(30_000L)
            }
            return if (pending.outcome.isCompleted) pending.outcome.await() else null
        } catch (_: SessionDeletingException) {
            return SystemTurnOutcome.Skipped(SystemTurnSkipReason.SESSION_MISSING)
        } catch (cancelled: CancellationException) {
            // 此处借用的是父轮，取消通知调用方不得取消父轮生成。
            throw cancelled
        } catch (_: Exception) {
            return SystemTurnOutcome.Failed(failureText(null))
        } finally {
            bridge.remove(pending)
        }
    }

    private suspend fun startCompletionTurn(
        delivered: SubagentCompletionSteering.Delivered,
        bridge: SubagentCompletionSteering,
        preparedAssistantId: String? = null,
        preparedUser: Message? = null,
    ) {
        val request = delivered.request
        val session = store.getSession(request.sessionId) ?: return
        val assistantId = preparedAssistantId ?: "ai_${UUID.randomUUID()}"
        var taskId: String? = null
        val generationTicket = SystemTurnAdmissionTicket()
        try {
            if (preparedAssistantId == null) {
                messages.addMessage(request.sessionId, Message(
                    id = assistantId, role = MessageRole.ASSISTANT, content = "", modelId = session.modelId,
                    parentMessageId = delivered.userMessageId,
                ))
            }
            val start = coordinator.start(GenerationRequest(
                sessionId = request.sessionId, assistantMessageId = assistantId,
                userMessageId = preparedUser?.id ?: delivered.userMessageId,
                userContent = preparedUser?.content ?: delivered.text,
                imageDataUrls = preparedUser?.userImages.orEmpty(),
                runtimePolicy = GenerationRuntimePolicy.BACKGROUND_ALLOWED,
                interactionMode = GenerationInteractionMode.HEADLESS,
            ), generationTicket::tryAccept)
            taskId = when (start) {
                is StartGenerationResult.Started -> start.taskId
                is StartGenerationResult.Queued -> start.taskId
                is StartGenerationResult.Existing -> start.snapshot.taskId
                is StartGenerationResult.Busy, is StartGenerationResult.Rejected -> null
            }
            val admittedTask = taskId
            if (admittedTask == null) {
                messages.discardPreparedMessages(request.sessionId, listOf(assistantId))
                notify(request, SystemTurnOutcome.Failed(failureText((start as? StartGenerationResult.Rejected)?.error)), null)
                return
            }
            bridge.trackGeneration {
                try {
                    finishTurn(request, admittedTask)
                } catch (cancelled: CancellationException) {
                    coordinator.cancel(admittedTask, CancellationReason.SHUTDOWN)
                    withContext(NonCancellable) { coordinator.awaitTaskCompletion(request.sessionId, admittedTask) }
                    throw cancelled
                } catch (_: Exception) {
                    coordinator.cancel(admittedTask, CancellationReason.SHUTDOWN)
                    withContext(NonCancellable) { coordinator.awaitTaskCompletion(request.sessionId, admittedTask) }
                    notify(request, SystemTurnOutcome.Failed(failureText(null)), null)
                }
            }
        } catch (cancelled: CancellationException) {
            val accepted = taskId ?: generationTicket.acceptedTaskId
            withContext(NonCancellable) {
                if (accepted == null) messages.discardPreparedMessages(request.sessionId, listOf(assistantId))
                else {
                    coordinator.cancel(accepted, CancellationReason.SHUTDOWN)
                    coordinator.awaitTaskCompletion(request.sessionId, accepted)
                }
            }
            throw cancelled
        } catch (_: Exception) {
            val accepted = taskId ?: generationTicket.acceptedTaskId
            if (accepted == null) messages.discardPreparedMessages(request.sessionId, listOf(assistantId))
            else {
                coordinator.cancel(accepted, CancellationReason.SHUTDOWN)
                withContext(NonCancellable) { coordinator.awaitTaskCompletion(request.sessionId, accepted) }
            }
            notify(request, SystemTurnOutcome.Failed(failureText(null)), null)
        }
    }

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
        return finishTurn(request, taskId)
    }

    private suspend fun finishTurn(request: SystemTurnRequest, taskId: String): SystemTurnOutcome {
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
