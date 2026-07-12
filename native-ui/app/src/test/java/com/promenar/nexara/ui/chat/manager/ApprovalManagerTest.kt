package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.*
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ToolExecutionKey
import com.promenar.nexara.data.repository.ToolExecutionLedger
import com.promenar.nexara.data.repository.ToolExecutionOutcome
import com.promenar.nexara.data.repository.ToolLedgerState
import com.promenar.nexara.data.repository.ToolTerminalRequest
import com.promenar.nexara.ui.chat.ChatStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalManagerTest {
    private lateinit var store: ChatStore
    private lateinit var approvalManager: ApprovalManager
    private lateinit var sessionManager: SessionManager
    private lateinit var ledger: ApprovalLedger
    private lateinit var messageManager: MessageManager
    private val testScope = TestScope()
    private val sessionUpdates = mutableListOf<Map<String, Any?>>()

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {}
        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {
            sessionUpdates += updates
        }
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): Session? = null
        override suspend fun getAll(): List<Session> = emptyList()
    }

    private val stubMessageRepo = object : IMessageRepository {
        override suspend fun insert(message: Message, sessionId: String) {}
        override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {}
        override suspend fun delete(messageId: String) {}
        override suspend fun deleteBySessionId(sessionId: String) {}
        override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) {}
        override suspend fun getById(messageId: String): Message? = null
        override suspend fun getBySession(sessionId: String): List<Message> = emptyList()
        override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) {}
    }

    private class ApprovalLedger : ToolExecutionLedger {
        val states = mutableMapOf<ToolExecutionKey, ToolLedgerState>()
        var completionTransition: com.promenar.nexara.data.repository.ApprovalTransition? = null
        override suspend fun register(key: ToolExecutionKey, toolName: String, requiresApproval: Boolean) =
            states.getOrPut(key) { if (requiresApproval) ToolLedgerState.PENDING_APPROVAL else ToolLedgerState.APPROVED }
        override suspend fun approve(keys: Set<ToolExecutionKey>) = transition(keys, ToolLedgerState.APPROVED)
        override suspend fun reject(keys: Set<ToolExecutionKey>) = transition(keys, ToolLedgerState.REJECTED)
        override suspend fun cancel(keys: Set<ToolExecutionKey>) = transition(keys, ToolLedgerState.CANCELLED)
        override suspend fun timeout(keys: Set<ToolExecutionKey>) = transition(keys, ToolLedgerState.TIMED_OUT)
        override suspend fun claim(key: ToolExecutionKey) = false
        override suspend fun finish(key: ToolExecutionKey, outcome: ToolExecutionOutcome) = false
        override suspend fun finishWithResult(
            key: ToolExecutionKey,
            toolName: String,
            content: String,
            thoughtSignature: String?,
            outcome: ToolExecutionOutcome,
            images: String?,
        ): Message? = null
        override suspend fun terminalizeWithResults(
            requests: Set<ToolTerminalRequest>,
            state: ToolLedgerState,
        ): List<Message> {
            val keys = requests.mapTo(mutableSetOf()) { it.key }
            transition(keys, state)
            return requests.filter { states[it.key] == state }.map {
                Message(
                    id = "terminal-${state.name}-${it.key.toolCallId}",
                    role = MessageRole.TOOL,
                    toolCallId = it.key.toolCallId,
                    name = it.toolName,
                    content = when (state) {
                        ToolLedgerState.REJECTED -> "工具执行已拒绝。"
                        ToolLedgerState.CANCELLED -> "工具执行已取消。"
                        ToolLedgerState.TIMED_OUT -> "工具审批已超时。"
                        else -> "工具执行未完成。"
                    },
                )
            }
        }
        override suspend fun state(key: ToolExecutionKey) = states[key]
        override suspend fun recoverInterruptedRunning(error: String) = 0
        override suspend fun createToolApproval(
            keySessionId: String,
            assistantMessageId: String,
            toolCalls: List<ToolCall>,
            pendingToolCallIds: Set<String>,
            request: ApprovalRequest,
        ): com.promenar.nexara.data.repository.ToolApprovalCreation {
            toolCalls.forEach { register(
                ToolExecutionKey(keySessionId, assistantMessageId, it.id),
                it.name,
                it.id in pendingToolCallIds,
            ) }
            return com.promenar.nexara.data.repository.ToolApprovalCreation.CREATED
        }
        override suspend fun completeToolApproval(sessionId: String, assistantMessageId: String) =
            completionTransition ?: com.promenar.nexara.data.repository.ApprovalTransition(
                sessionId,
                assistantMessageId,
                LoopStatus.RUNNING,
                null,
            )
        override suspend fun recoverApprovalState() = 0
        private fun transition(keys: Set<ToolExecutionKey>, target: ToolLedgerState): Int = keys.count { key ->
            if (states[key] == ToolLedgerState.PENDING_APPROVAL ||
                (target == ToolLedgerState.CANCELLED && states[key] == ToolLedgerState.APPROVED) ||
                (target == ToolLedgerState.TIMED_OUT && states[key] == ToolLedgerState.APPROVED)
            ) {
                states[key] = target
                true
            } else false
        }
    }

    @Before
    fun setUp() {
        sessionUpdates.clear()
        store = ChatStore()
        sessionManager = SessionManager(store, stubSessionRepo)
        ledger = ApprovalLedger()
        messageManager = MessageManager(store, stubMessageRepo, stubSessionRepo, testScope)
        approvalManager = ApprovalManager(store, ledger, messageManager, stubSessionRepo)
    }

    private suspend fun seedSessionWithAssistant(
        sessionId: String = "s1",
        toolCalls: List<ToolCall>? = null,
        pendingApprovalToolIds: List<String>? = null
    ): Session {
        val session = Session(id = sessionId, agentId = "a1", title = "Test")
        sessionManager.addSession(session)
        testScope.advanceUntilIdle()

        val msg = Message(
            id = "m1",
            role = MessageRole.ASSISTANT,
            content = "I'll help you",
            createdAt = 1000L,
            toolCalls = toolCalls,
            pendingApprovalToolIds = pendingApprovalToolIds
        )
        store.updateSession(sessionId) { s ->
            s.copy(messages = s.messages + msg)
        }
        return session
    }

    @Test
    fun setApprovalRequest() = testScope.runTest {
        seedSessionWithAssistant()
        val request = ApprovalRequest(toolName = "read_file", args = "{}", reason = "Needs approval")
        approvalManager.setApprovalRequest("s1", request)

        val session = store.getSession("s1")!!
        assertThat(session.approvalRequest).isNotNull()
        assertThat(session.approvalRequest!!.toolName).isEqualTo("read_file")
        assertThat(sessionUpdates.last()["approvalRequest"]).isEqualTo(request)
    }

    @Test
    fun clearApprovalRequest() = testScope.runTest {
        seedSessionWithAssistant()
        approvalManager.setApprovalRequest("s1", ApprovalRequest(toolName = "read"))
        approvalManager.setApprovalRequest("s1", null)

        val session = store.getSession("s1")!!
        assertThat(session.approvalRequest).isNull()
    }

    @Test
    fun setExecutionMode() = testScope.runTest {
        seedSessionWithAssistant()
        approvalManager.setExecutionMode("s1", "manual")

        val session = store.getSession("s1")!!
        assertThat(session.executionMode).isEqualTo("manual")
    }

    @Test
    fun setLoopStatus() = testScope.runTest {
        seedSessionWithAssistant()
        approvalManager.setLoopStatus("s1", LoopStatus.RUNNING)

        val session = store.getSession("s1")!!
        assertThat(session.loopStatus).isEqualTo(LoopStatus.RUNNING)
        assertThat(sessionUpdates.last()["loopStatus"]).isEqualTo(LoopStatus.RUNNING)
    }

    @Test
    fun setPendingIntervention() = testScope.runTest {
        seedSessionWithAssistant()
        approvalManager.setPendingIntervention("s1", "Use alternative approach")

        val session = store.getSession("s1")!!
        assertThat(session.pendingIntervention).isEqualTo("Use alternative approach")
    }

    @Test
    fun resumeGenerationRejectsAndPauses() = testScope.runTest {
        seedSessionWithAssistant()
        approvalManager.setApprovalRequest("s1", ApprovalRequest(toolName = "read"))

        approvalManager.resumeGeneration("s1", approved = false)

        val session = store.getSession("s1")!!
        assertThat(session.loopStatus).isEqualTo(LoopStatus.PAUSED)
        assertThat(session.approvalRequest).isNull()
    }

    @Test
    fun resumeGenerationContinuationReject() = testScope.runTest {
        seedSessionWithAssistant()
        approvalManager.setApprovalRequest("s1", ApprovalRequest(type = "continuation"))

        approvalManager.resumeGeneration("s1", approved = false)

        val session = store.getSession("s1")!!
        assertThat(session.loopStatus).isEqualTo(LoopStatus.COMPLETED)
        assertThat(session.approvalRequest).isNull()
    }

    @Test
    fun resumeGenerationApprovedAddsDecisionStep() = testScope.runTest {
        seedSessionWithAssistant()
        approvalManager.setApprovalRequest("s1", ApprovalRequest(toolName = "read"))

        approvalManager.resumeGeneration("s1", approved = true)

        val session = store.getSession("s1")!!
        val lastMsg = session.messages.find { it.id == "m1" }!!
        val decisionStep = lastMsg.executionSteps?.lastOrNull()
        assertThat(decisionStep).isNotNull()
        assertThat(decisionStep!!.type).isEqualTo("intervention_result")
        assertThat(decisionStep.content).contains("Approved")
    }

    @Test
    fun resumeGenerationWithIntervention() = testScope.runTest {
        seedSessionWithAssistant()
        approvalManager.setApprovalRequest("s1", ApprovalRequest(toolName = "read"))

        approvalManager.resumeGeneration("s1", approved = true, intervention = "Do it differently")

        val session = store.getSession("s1")!!
        assertThat(session.pendingIntervention).isEqualTo("Do it differently")
        val lastMsg = session.messages.find { it.id == "m1" }!!
        val decisionStep = lastMsg.executionSteps?.lastOrNull()
        assertThat(decisionStep!!.content).contains("Human Instruction: Do it differently")
    }

    @Test
    fun resumeGenerationContinuationApprovedIncreasesBudget() = testScope.runTest {
        val session = seedSessionWithAssistant()
        approvalManager.setApprovalRequest("s1", ApprovalRequest(type = "continuation"))

        approvalManager.resumeGeneration("s1", approved = true)

        val updatedSession = store.getSession("s1")!!
        assertThat(updatedSession.continuationBudget).isEqualTo(session.autoLoopLimit)
    }

    @Test
    fun setKGExtractionStatus() = testScope.runTest {
        seedSessionWithAssistant()

        sessionManager.setKGExtractionStatus("s1", true)
        assertThat(store.get().activeKGExtractions).contains("s1")

        sessionManager.setKGExtractionStatus("s1", false)
        assertThat(store.get().activeKGExtractions).doesNotContain("s1")
    }

    @Test
    fun approvalPassesOnlyPendingToolCallIdsAndDoesNotReplaySafeCalls() = testScope.runTest {
        val calls = listOf(
            ToolCall("safe", "read_file", "{}"),
            ToolCall("risky", "write_file", "{}"),
        )
        seedSessionWithAssistant(toolCalls = calls, pendingApprovalToolIds = listOf("risky"))
        val riskyKey = ToolExecutionKey("s1", "m1", "risky")
        ledger.register(riskyKey, "write_file", requiresApproval = true)
        approvalManager.setApprovalRequest("s1", ApprovalRequest(type = "tool_approval"))
        var executedIds: Set<String>? = null
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { _, _, allowedIds -> executedIds = allowedIds },
        )

        approvalManager.resumeGeneration("s1", approved = true)

        assertThat(executedIds).containsExactly("risky")
        assertThat(ledger.state(riskyKey)).isEqualTo(ToolLedgerState.APPROVED)
        assertThat(store.getSession("s1")!!.messages.first { it.id == "m1" }.pendingApprovalToolIds)
            .isNull()
    }

    @Test
    fun rejectionWritesLedgerTerminalStateAndOneToolMessagePerPendingCall() = testScope.runTest {
        val calls = listOf(
            ToolCall("r1", "write_file", "{}"),
            ToolCall("r2", "delete_file", "{}"),
        )
        seedSessionWithAssistant(toolCalls = calls, pendingApprovalToolIds = listOf("r1", "r2"))
        val keys = calls.map { ToolExecutionKey("s1", "m1", it.id) }.toSet()
        calls.zip(keys).forEach { (call, key) -> ledger.register(key, call.name, true) }
        approvalManager.setApprovalRequest("s1", ApprovalRequest(type = "tool_approval"))

        approvalManager.resumeGeneration("s1", approved = false)
        advanceUntilIdle()

        assertThat(keys.map { ledger.state(it) }).containsExactly(
            ToolLedgerState.REJECTED,
            ToolLedgerState.REJECTED,
        )
        val toolMessages = store.getSession("s1")!!.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages.mapNotNull { it.toolCallId }).containsExactly("r1", "r2")
        assertThat(toolMessages.all { it.content.contains("已拒绝") }).isTrue()
    }

    @Test
    fun cancelAndTimeoutHaveExplicitTerminalEntrypoints() = testScope.runTest {
        suspend fun seedPending(sessionId: String, messageId: String, toolCallId: String): ToolExecutionKey {
            seedSessionWithAssistant(
                sessionId = sessionId,
                toolCalls = listOf(ToolCall(toolCallId, "write_file", "{}")),
                pendingApprovalToolIds = listOf(toolCallId),
            )
            val key = ToolExecutionKey(sessionId, messageId, toolCallId)
            ledger.register(key, "write_file", true)
            approvalManager.setApprovalRequest(sessionId, ApprovalRequest(type = "tool_approval"))
            return key
        }
        val cancelled = seedPending("cancel-session", "m1", "cancelled")
        val timedOut = seedPending("timeout-session", "m1", "timed-out")

        approvalManager.cancelPendingApproval("cancel-session")
        approvalManager.timeoutPendingApproval("timeout-session")
        advanceUntilIdle()

        assertThat(ledger.state(cancelled)).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(ledger.state(timedOut)).isEqualTo(ToolLedgerState.TIMED_OUT)
        assertThat(store.getSession("cancel-session")!!.messages.last().content).contains("已取消")
        assertThat(store.getSession("timeout-session")!!.messages.last().content).contains("已超时")
    }

    @Test
    fun crashAfterApproveBeforeExecuteKeepsPersistedApprovalForExplicitRecovery() = testScope.runTest {
        val call = ToolCall("risky", "write_file", "{}")
        seedSessionWithAssistant(
            toolCalls = listOf(call),
            pendingApprovalToolIds = listOf(call.id),
        )
        val key = ToolExecutionKey("s1", "m1", call.id)
        ledger.register(key, call.name, true)
        val request = ApprovalRequest(type = "tool_approval", toolName = call.name)
        approvalManager.setApprovalRequest("s1", request)
        approvalManager.setLoopStatus("s1", LoopStatus.WAITING_FOR_APPROVAL)
        approvalManager.setCallbacks(
            onGenerateMessage = null,
            onExecuteTools = { _, _, _ -> throw IllegalStateException("模拟进程在claim前终止") },
        )

        val failure = runCatching {
            approvalManager.resumeGeneration("s1", approved = true)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.APPROVED)
        assertThat(store.getSession("s1")!!.approvalRequest).isEqualTo(request)
        assertThat(store.getSession("s1")!!.loopStatus).isEqualTo(LoopStatus.WAITING_FOR_APPROVAL)
        assertThat(store.getSession("s1")!!.messages.single().pendingApprovalToolIds)
            .containsExactly(call.id)
        val persistedApprovalUpdates = sessionUpdates
            .filter { "approvalRequest" in it }
            .map { it["approvalRequest"] }
        assertThat(persistedApprovalUpdates).containsExactly(request)
    }

    @Test
    fun approvalTransitionToNextGroupKeepsWaitingAndDoesNotGenerate() = testScope.runTest {
        val first = ToolCall("first", "write_file", "{}")
        val next = ToolCall("next", "write_file", "{}")
        seedSessionWithAssistant(toolCalls = listOf(first), pendingApprovalToolIds = listOf(first.id))
        store.updateSession("s1") { session ->
            session.copy(messages = session.messages + Message(
                id = "m2",
                role = MessageRole.ASSISTANT,
                content = "next",
                toolCalls = listOf(next),
            ))
        }
        ledger.register(ToolExecutionKey("s1", "m1", first.id), first.name, true)
        approvalManager.setApprovalRequest("s1", ApprovalRequest(type = "tool_approval"))
        ledger.completionTransition = com.promenar.nexara.data.repository.ApprovalTransition(
            sessionId = "s1",
            currentAssistantMessageId = "m1",
            loopStatus = LoopStatus.WAITING_FOR_APPROVAL,
            approvalRequest = ApprovalRequest(toolName = next.name, type = "tool_approval"),
            nextAssistantMessageId = "m2",
            nextPendingToolCallIds = listOf(next.id),
        )
        var generateCount = 0
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> generateCount += 1 },
            onExecuteTools = { _, _, _ -> },
        )

        approvalManager.resumeGeneration("s1", approved = true)

        assertThat(generateCount).isEqualTo(0)
        assertThat(store.getSession("s1")!!.loopStatus).isEqualTo(LoopStatus.WAITING_FOR_APPROVAL)
        assertThat(store.getSession("s1")!!.approvalRequest?.toolName).isEqualTo(next.name)
        assertThat(store.getSession("s1")!!.messages.first { it.id == "m2" }.pendingApprovalToolIds)
            .containsExactly(next.id)
    }
}
