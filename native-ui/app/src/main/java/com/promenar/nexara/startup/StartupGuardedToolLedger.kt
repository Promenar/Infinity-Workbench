package com.promenar.nexara.startup

import com.promenar.nexara.data.repository.*
import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.domain.media.ViewedMedia

/** 停用台账时连旧审批入口也失败关闭；仅当前台账恢复动作可重新访问。 */
class StartupGuardedToolLedger(private val delegate: ToolExecutionLedger) : ToolExecutionLedger {
    private suspend fun <T> checked(block: suspend () -> T): T {
        StartupWriteBarrier.requireWritable(subsystem = StartupSubsystem.TOOL_LEDGER)
        return block()
    }
    override suspend fun register(key: ToolExecutionKey, identity: ToolInvocationIdentity): ToolRegistrationResult = checked { delegate.register(key, identity) }
    override suspend fun approve(keys: Set<ToolExecutionKey>): Int = checked { delegate.approve(keys) }
    override suspend fun reject(keys: Set<ToolExecutionKey>): Int = checked { delegate.reject(keys) }
    override suspend fun cancel(keys: Set<ToolExecutionKey>): Int = checked { delegate.cancel(keys) }
    override suspend fun timeout(keys: Set<ToolExecutionKey>): Int = checked { delegate.timeout(keys) }
    override suspend fun claim(key: ToolExecutionKey, expectedIdentity: ToolInvocationIdentity): Boolean = checked { delegate.claim(key, expectedIdentity) }
    override suspend fun finish(key: ToolExecutionKey, outcome: ToolExecutionOutcome): Boolean = checked { delegate.finish(key, outcome) }
    override suspend fun finishWithResult(key: ToolExecutionKey, toolName: String, content: String, thoughtSignature: String?, outcome: ToolExecutionOutcome, images: String?, viewedMedia: List<ViewedMedia>?): Message? = checked { delegate.finishWithResult(key, toolName, content, thoughtSignature, outcome, images, viewedMedia) }
    override suspend fun terminalizeWithResults(requests: Set<ToolTerminalRequest>, state: ToolLedgerState): List<Message> = checked { delegate.terminalizeWithResults(requests, state) }
    override suspend fun state(key: ToolExecutionKey): ToolLedgerState? = checked { delegate.state(key) }
    override suspend fun invocationIdentity(key: ToolExecutionKey): ToolInvocationIdentity? = checked { delegate.invocationIdentity(key) }
    override suspend fun failAwaitingIdentityConflict(key: ToolExecutionKey, expectedIdentity: ToolInvocationIdentity, thoughtSignature: String?): Message? = checked { delegate.failAwaitingIdentityConflict(key, expectedIdentity, thoughtSignature) }
    override suspend fun recoverInterruptedRunning(error: String): Int = checked { delegate.recoverInterruptedRunning(error) }
    override suspend fun createToolApproval(keySessionId: String, assistantMessageId: String, toolCalls: List<ToolCall>, pendingToolCallIds: Set<String>, request: ApprovalRequest): ToolApprovalCreation = checked { delegate.createToolApproval(keySessionId, assistantMessageId, toolCalls, pendingToolCallIds, request) }
    override suspend fun decideExactToolApproval(sessionId: String, request: ApprovalRequest, approved: Boolean): ExactToolApprovalDecision = checked { delegate.decideExactToolApproval(sessionId, request, approved) }
    override suspend fun completeExactToolApproval(sessionId: String, request: ApprovalRequest): ExactToolApprovalCompletion = checked { delegate.completeExactToolApproval(sessionId, request) }
    override suspend fun compensateExactToolApproval(sessionId: String, request: ApprovalRequest, state: ToolLedgerState, error: String): ExactToolApprovalDecision = checked { delegate.compensateExactToolApproval(sessionId, request, state, error) }
    override suspend fun completeToolApproval(sessionId: String, assistantMessageId: String): ApprovalTransition = checked { delegate.completeToolApproval(sessionId, assistantMessageId) }
    override suspend fun recoverApprovalState(): Int = checked { delegate.recoverApprovalState() }
}
