package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.LoopStatus
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.repository.ToolExecutionKey
import com.promenar.nexara.data.repository.ToolExecutionLedger
import com.promenar.nexara.data.repository.ToolLedgerState
import com.promenar.nexara.data.repository.ToolTerminalRequest
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.repository.ApprovalTransition
import com.promenar.nexara.data.repository.ExactToolApprovalCompletion
import com.promenar.nexara.data.repository.ExactToolApprovalDecision
import com.promenar.nexara.data.repository.ToolApprovalRequestFactory
import com.promenar.nexara.domain.model.ExecutionModeCodec
import com.promenar.nexara.ui.chat.ChatStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class ApprovalManager(
    private val store: ChatStore,
    private val ledger: ToolExecutionLedger,
    private val messageManager: MessageManager,
    private val sessionRepository: ISessionRepository,
) {
    private val approvalLocks = ConcurrentHashMap<String, Mutex>()
    private var onGenerateMessage: (suspend (sessionId: String, content: String, isResumption: Boolean) -> Unit)? = null
    private var onExecuteTools: (suspend (
        sessionId: String,
        targetMessageId: String,
        allowedToolCallIds: Set<String>,
    ) -> Unit)? = null

    fun setCallbacks(
        onGenerateMessage: (suspend (sessionId: String, content: String, isResumption: Boolean) -> Unit)?,
        onExecuteTools: (suspend (
            sessionId: String,
            targetMessageId: String,
            allowedToolCallIds: Set<String>,
        ) -> Unit)?
    ) {
        this.onGenerateMessage = onGenerateMessage
        this.onExecuteTools = onExecuteTools
    }

    suspend fun setApprovalRequest(sessionId: String, request: ApprovalRequest?) {
        sessionRepository.updatePartial(sessionId, mapOf("approvalRequest" to request))
        store.updateSession(sessionId) { s ->
            s.copy(approvalRequest = request)
        }
    }

    suspend fun resumeGeneration(
        sessionId: String,
        expectedRequest: ApprovalRequest? = null,
        approved: Boolean = true,
        intervention: String? = null
    ) = approvalLock(sessionId).withLock {
        resumeGenerationLocked(sessionId, expectedRequest, approved, intervention)
    }

    private suspend fun resumeGenerationLocked(
        sessionId: String,
        expectedRequest: ApprovalRequest?,
        approved: Boolean,
        intervention: String?,
    ) {
        val session = store.getSession(sessionId) ?: return
        val approvalRequest = session.approvalRequest ?: return

        val isContinuation = approvalRequest.type == "continuation"

        if (!isContinuation) {
            val exact = expectedRequest ?: return
            if (approvalRequest != exact || !ToolApprovalRequestFactory.isValid(exact)) return
            resumeExactToolApproval(sessionId, session, exact, approved, intervention)
            return
        }

        val targetMsg = if (isContinuation) {
            session.messages.lastOrNull { it.role == com.promenar.nexara.data.model.MessageRole.ASSISTANT }
        } else {
            session.messages.lastOrNull {
                it.role == com.promenar.nexara.data.model.MessageRole.ASSISTANT &&
                        !it.pendingApprovalToolIds.isNullOrEmpty()
            } ?: session.messages.lastOrNull()
        }

        if (targetMsg != null && targetMsg.role == com.promenar.nexara.data.model.MessageRole.ASSISTANT) {
            val decisionStep = ExecutionStep(
                id = "dec_${System.currentTimeMillis()}",
                type = "intervention_result",
                content = when {
                    intervention != null -> "Human Instruction: $intervention"
                    approved -> if (isContinuation) "User Approved Continuation (+${session.autoLoopLimit} Loops)" else "User Approved"
                    else -> if (isContinuation) "User Ended Task" else "User Rejected"
                },
                timestamp = System.currentTimeMillis()
            )

            store.updateMessageInSession(sessionId, targetMsg.id) { m ->
                m.copy(
                    executionSteps = (m.executionSteps ?: emptyList())
                        .filter { it.type != "intervention_required" } + decisionStep
                )
            }
        }

        if (intervention != null) {
            setPendingIntervention(sessionId, intervention)
        }

        if (!approved && intervention == null && !isContinuation) {
            if (targetMsg != null) {
                terminalPendingTools(sessionId, targetMsg.id, ToolLedgerState.REJECTED)
                mirrorClosedApproval(sessionId, targetMsg.id, LoopStatus.PAUSED)
            }
            return
        }

        if (isContinuation && !approved && intervention == null) {
            setLoopStatus(sessionId, LoopStatus.COMPLETED)
            setApprovalRequest(sessionId, null)
            return
        }

        if (approved && intervention == null && targetMsg != null) {
            val toolsToExecute = targetMsg.toolCalls?.let { allCalls ->
                val pendingIds = targetMsg.pendingApprovalToolIds ?: emptyList()
                if (pendingIds.isNotEmpty()) {
                    allCalls.filter { pendingIds.contains(it.id) }
                } else {
                    allCalls
                }
            } ?: emptyList()

            val keys = toolsToExecute.mapTo(mutableSetOf()) {
                ToolExecutionKey(sessionId, targetMsg.id, it.id)
            }
            ledger.approve(keys)

            if (toolsToExecute.isNotEmpty()) {
                onExecuteTools?.invoke(
                    sessionId,
                    targetMsg.id,
                    toolsToExecute.mapTo(mutableSetOf()) { it.id },
                )
            }
            val transition = ledger.completeToolApproval(sessionId, targetMsg.id)
            mirrorApprovalTransition(transition)
            if (transition.loopStatus == LoopStatus.WAITING_FOR_APPROVAL) return
        }

        if (isContinuation) {
            setApprovalRequest(sessionId, null)
            setLoopStatus(sessionId, LoopStatus.RUNNING)
        }

        if (isContinuation && approved) {
            val currentBudget = session.continuationBudget
            val stepSize = session.autoLoopLimit
            store.updateSession(sessionId) { s ->
                s.copy(continuationBudget = currentBudget + stepSize)
            }
        }

        onGenerateMessage?.invoke(sessionId, intervention ?: "", true)
    }

    private suspend fun resumeExactToolApproval(
        sessionId: String,
        session: com.promenar.nexara.data.model.Session,
        request: ApprovalRequest,
        approved: Boolean,
        intervention: String?,
    ) {
        if (approved && onExecuteTools == null) return
        val assistantMessageId = request.assistantMessageId ?: return
        val targetMessage = session.messages.firstOrNull {
            it.id == assistantMessageId && it.role == MessageRole.ASSISTANT
        } ?: return
        val decision = ledger.decideExactToolApproval(sessionId, request, approved)
        if (decision !is ExactToolApprovalDecision.Decided) return

        val decisionStep = ExecutionStep(
            id = "dec_${System.currentTimeMillis()}",
            type = "intervention_result",
            content = when {
                intervention != null -> "Human Instruction: $intervention"
                approved -> "User Approved"
                else -> "User Rejected"
            },
            timestamp = System.currentTimeMillis(),
        )
        store.updateMessageInSession(sessionId, assistantMessageId) { message ->
            message.copy(
                executionSteps = message.executionSteps.orEmpty()
                    .filter { it.type != "intervention_required" } + decisionStep,
            )
        }
        if (intervention != null) setPendingIntervention(sessionId, intervention)

        decision.terminalMessages.forEach { message ->
            messageManager.mirrorPersistedMessage(sessionId, message)
        }
        if (approved) {
            try {
                onExecuteTools?.invoke(
                    sessionId,
                    assistantMessageId,
                    setOf(decision.key.toolCallId),
                )
            } catch (cancelled: CancellationException) {
                try {
                    withContext(NonCancellable) {
                        completeOrCompensate(
                            sessionId,
                            request,
                            ToolLedgerState.CANCELLED,
                            "工具执行协程已取消",
                        )
                    }
                } catch (cleanupFailure: Throwable) {
                    if (cleanupFailure !== cancelled) cancelled.addSuppressed(cleanupFailure)
                }
                throw cancelled
            } catch (error: Exception) {
                val completed = completeOrCompensate(
                    sessionId,
                    request,
                    ToolLedgerState.FAILED,
                    error.message ?: error::class.java.simpleName,
                )
                if (completed != null && completed.transition.loopStatus != LoopStatus.WAITING_FOR_APPROVAL) {
                    onGenerateMessage?.invoke(sessionId, intervention.orEmpty(), true)
                }
                return
            }
        }
        val directCompletion = ledger.completeExactToolApproval(sessionId, request)
        val completion = if (directCompletion is ExactToolApprovalCompletion.Completed) {
            mirrorApprovalTransition(directCompletion.transition)
            directCompletion
        } else if (approved) {
            completeOrCompensate(
                sessionId,
                request,
                ToolLedgerState.FAILED,
                "工具执行未写入稳定终态",
            ) ?: return
        } else {
            return
        }
        if (completion.transition.loopStatus == LoopStatus.WAITING_FOR_APPROVAL || !approved) return
        onGenerateMessage?.invoke(sessionId, intervention.orEmpty(), true)
    }

    suspend fun cancelPendingApproval(sessionId: String) {
        approvalLock(sessionId).withLock {
            terminateCurrentApproval(sessionId, ToolLedgerState.CANCELLED)
        }
    }

    suspend fun timeoutPendingApproval(sessionId: String) {
        approvalLock(sessionId).withLock {
            terminateCurrentApproval(sessionId, ToolLedgerState.TIMED_OUT)
        }
    }

    private fun approvalLock(sessionId: String): Mutex =
        approvalLocks.computeIfAbsent(sessionId) { Mutex() }

    private suspend fun completeOrCompensate(
        sessionId: String,
        request: ApprovalRequest,
        state: ToolLedgerState,
        error: String,
    ): ExactToolApprovalCompletion.Completed? {
        val existing = ledger.completeExactToolApproval(sessionId, request)
        if (existing is ExactToolApprovalCompletion.Completed) {
            mirrorApprovalTransition(existing.transition)
            return existing
        }
        val compensation = ledger.compensateExactToolApproval(sessionId, request, state, error)
        if (compensation !is ExactToolApprovalDecision.Decided) return null
        compensation.terminalMessages.forEach { message ->
            messageManager.mirrorPersistedMessage(sessionId, message)
        }
        val completion = ledger.completeExactToolApproval(sessionId, request)
        if (completion !is ExactToolApprovalCompletion.Completed) return null
        mirrorApprovalTransition(completion.transition)
        return completion
    }

    private suspend fun terminateCurrentApproval(sessionId: String, state: ToolLedgerState) {
        val session = store.getSession(sessionId) ?: return
        val target = session.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT && !it.pendingApprovalToolIds.isNullOrEmpty()
        } ?: return
        terminalPendingTools(sessionId, target.id, state)
        mirrorClosedApproval(sessionId, target.id, LoopStatus.PAUSED)
    }

    private suspend fun mirrorClosedApproval(
        sessionId: String,
        assistantMessageId: String,
        loopStatus: LoopStatus,
    ) {
        messageManager.mirrorPersistedPendingApprovalState(sessionId, assistantMessageId, null)
        store.updateSession(sessionId) {
            it.copy(approvalRequest = null, loopStatus = loopStatus)
        }
    }

    private suspend fun mirrorApprovalTransition(transition: ApprovalTransition) {
        messageManager.mirrorPersistedPendingApprovalState(
            transition.sessionId,
            transition.currentAssistantMessageId,
            null,
        )
        transition.terminalMessages.forEach { message ->
            messageManager.mirrorPersistedMessage(transition.sessionId, message)
        }
        transition.nextAssistantMessageId?.let { nextId ->
            messageManager.mirrorPersistedPendingApprovalState(
                transition.sessionId,
                nextId,
                transition.nextPendingToolCallIds,
            )
        }
        store.updateSession(transition.sessionId) {
            it.copy(
                approvalRequest = transition.approvalRequest,
                loopStatus = transition.loopStatus,
            )
        }
    }

    private suspend fun terminalPendingTools(
        sessionId: String,
        assistantMessageId: String,
        state: ToolLedgerState,
    ) {
        val session = store.getSession(sessionId) ?: return
        val target = session.messages.firstOrNull { it.id == assistantMessageId } ?: return
        val pendingIds = target.pendingApprovalToolIds.orEmpty().toSet()
        if (pendingIds.isEmpty()) return
        val requests = target.toolCalls.orEmpty()
            .filter { it.id in pendingIds }
            .mapTo(mutableSetOf()) { call ->
                ToolTerminalRequest(
                    key = ToolExecutionKey(sessionId, assistantMessageId, call.id),
                    toolName = call.name,
                    thoughtSignature = target.thoughtSignature,
                )
            }
        ledger.terminalizeWithResults(requests, state).forEach { message ->
            messageManager.mirrorPersistedMessage(sessionId, message)
        }
    }

    suspend fun setExecutionMode(sessionId: String, mode: String) {
        val normalized = ExecutionModeCodec.serialize(ExecutionModeCodec.parseOrSemi(mode))
        sessionRepository.updatePartial(sessionId, mapOf("executionMode" to normalized))
        store.updateSession(sessionId) { s ->
            s.copy(executionMode = normalized)
        }
    }

    suspend fun setLoopStatus(sessionId: String, status: LoopStatus) {
        sessionRepository.updatePartial(sessionId, mapOf("loopStatus" to status))
        store.updateSession(sessionId) { s ->
            s.copy(loopStatus = status)
        }
    }

    fun setPendingIntervention(sessionId: String, intervention: String?) {
        store.updateSession(sessionId) { s ->
            s.copy(pendingIntervention = intervention)
        }
    }
}
