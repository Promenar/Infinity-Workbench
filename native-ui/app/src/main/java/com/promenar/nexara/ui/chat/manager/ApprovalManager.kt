package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.LoopStatus
import com.promenar.nexara.ui.chat.ChatStore

class ApprovalManager(
    private val store: ChatStore
) {
    private var onGenerateMessage: (suspend (sessionId: String, content: String, isResumption: Boolean) -> Unit)? = null
    private var onExecuteTools: (suspend (sessionId: String, targetMessageId: String) -> Unit)? = null

    fun setCallbacks(
        onGenerateMessage: (suspend (sessionId: String, content: String, isResumption: Boolean) -> Unit)?,
        onExecuteTools: (suspend (sessionId: String, targetMessageId: String) -> Unit)?
    ) {
        this.onGenerateMessage = onGenerateMessage
        this.onExecuteTools = onExecuteTools
    }

    fun setApprovalRequest(sessionId: String, request: ApprovalRequest?) {
        store.updateSession(sessionId) { s ->
            s.copy(approvalRequest = request)
        }
    }

    suspend fun resumeGeneration(
        sessionId: String,
        approved: Boolean = true,
        intervention: String? = null
    ) {
        val session = store.getSession(sessionId) ?: return
        val approvalRequest = session.approvalRequest ?: return

        val isContinuation = approvalRequest.type == "continuation"

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
            setLoopStatus(sessionId, LoopStatus.PAUSED)
            setApprovalRequest(sessionId, null)
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

            if (toolsToExecute.isNotEmpty()) {
                onExecuteTools?.invoke(sessionId, targetMsg.id)
            }

            store.updateMessageInSession(sessionId, targetMsg.id) { m ->
                m.copy(pendingApprovalToolIds = null)
            }
        }

        setApprovalRequest(sessionId, null)
        setLoopStatus(sessionId, LoopStatus.RUNNING)

        if (isContinuation && approved) {
            val currentBudget = session.continuationBudget
            val stepSize = session.autoLoopLimit
            store.updateSession(sessionId) { s ->
                s.copy(continuationBudget = currentBudget + stepSize)
            }
        }

        onGenerateMessage?.invoke(sessionId, intervention ?: "", true)
    }

    fun setExecutionMode(sessionId: String, mode: String) {
        store.updateSession(sessionId) { s ->
            s.copy(executionMode = mode)
        }
    }

    fun setLoopStatus(sessionId: String, status: LoopStatus) {
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
