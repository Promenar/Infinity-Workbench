package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.*
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ToolExecutionKey
import com.promenar.nexara.data.repository.ToolExecutionLedger
import com.promenar.nexara.data.repository.ToolExecutionOutcome
import com.promenar.nexara.data.repository.ToolInvocationIdentity
import com.promenar.nexara.data.repository.ToolInvocationIdentityFactory
import com.promenar.nexara.data.repository.ToolInvocationIdentityResolution
import com.promenar.nexara.data.repository.ToolLedgerState
import com.promenar.nexara.data.repository.ToolRegistrationResult
import com.promenar.nexara.data.repository.ToolTerminalRequest
import com.promenar.nexara.data.repository.ApprovalCallCandidate
import com.promenar.nexara.data.repository.ExactToolApprovalCompletion
import com.promenar.nexara.data.repository.ExactToolApprovalDecision
import com.promenar.nexara.data.repository.ToolApprovalRequestFactory
import com.promenar.nexara.data.repository.toInvocationIdentity
import com.promenar.nexara.ui.chat.ChatStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
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
        var suspendCompensationCancellably = false
        private val identities = mutableMapOf<ToolExecutionKey, ToolInvocationIdentity>()
        var completionTransition: com.promenar.nexara.data.repository.ApprovalTransition? = null
        override suspend fun register(
            key: ToolExecutionKey,
            identity: ToolInvocationIdentity,
        ): ToolRegistrationResult {
            val existing = identities[key]
            if (existing != null) {
                return if (existing == identity) {
                    ToolRegistrationResult.Existing(states.getValue(key))
                } else {
                    ToolRegistrationResult.Conflict
                }
            }
            identities[key] = identity
            val state = if (identity.requiresApproval) {
                ToolLedgerState.PENDING_APPROVAL
            } else {
                ToolLedgerState.APPROVED
            }
            states[key] = state
            return ToolRegistrationResult.Registered(state)
        }

        suspend fun register(key: ToolExecutionKey, toolName: String, requiresApproval: Boolean) =
            register(
                key,
                (ToolInvocationIdentityFactory.fromLegacyToolCall(
                    ToolCall(key.toolCallId, toolName, "{}"),
                    requiresApproval,
                ) as ToolInvocationIdentityResolution.Valid).identity,
            )
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
        override suspend fun invocationIdentity(key: ToolExecutionKey) = identities[key]
        override suspend fun recoverInterruptedRunning(error: String) = 0
        override suspend fun createToolApproval(
            keySessionId: String,
            assistantMessageId: String,
            toolCalls: List<ToolCall>,
            pendingToolCallIds: Set<String>,
            request: ApprovalRequest,
        ): com.promenar.nexara.data.repository.ToolApprovalCreation {
            toolCalls.forEach {
                val identity = ToolInvocationIdentityFactory.fromLegacyToolCall(
                    it,
                    it.id in pendingToolCallIds,
                )
                if (identity !is ToolInvocationIdentityResolution.Valid) {
                    return com.promenar.nexara.data.repository.ToolApprovalCreation.CONFLICT
                }
                if (register(
                        ToolExecutionKey(keySessionId, assistantMessageId, it.id),
                        identity.identity,
                    ) == ToolRegistrationResult.Conflict
                ) return com.promenar.nexara.data.repository.ToolApprovalCreation.CONFLICT
            }
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
        override suspend fun decideExactToolApproval(
            sessionId: String,
            request: ApprovalRequest,
            approved: Boolean,
        ): ExactToolApprovalDecision {
            if (!ToolApprovalRequestFactory.isValid(request)) return ExactToolApprovalDecision.Conflict
            val current = request.calls.firstOrNull() ?: return ExactToolApprovalDecision.Conflict
            val key = ToolExecutionKey(sessionId, request.assistantMessageId!!, current.toolCallId)
            val identity = identities[key] ?: return ExactToolApprovalDecision.Conflict
            if (identity != current.toInvocationIdentity() || states[key] != ToolLedgerState.PENDING_APPROVAL) {
                return ExactToolApprovalDecision.Conflict
            }
            states[key] = if (approved) ToolLedgerState.APPROVED else ToolLedgerState.REJECTED
            val terminalMessages = if (approved) emptyList() else listOf(
                Message(
                    id = "terminal-rejected-${current.toolCallId}",
                    role = MessageRole.TOOL,
                    toolCallId = current.toolCallId,
                    parentMessageId = request.assistantMessageId,
                    name = current.toolName,
                    content = "工具执行已拒绝。",
                ),
            )
            return ExactToolApprovalDecision.Decided(key, terminalMessages)
        }
        override suspend fun completeExactToolApproval(
            sessionId: String,
            request: ApprovalRequest,
        ): ExactToolApprovalCompletion {
            completionTransition?.let { return ExactToolApprovalCompletion.Completed(it) }
            val currentKey = ToolExecutionKey(
                sessionId,
                request.assistantMessageId!!,
                request.calls.first().toolCallId,
            )
            if (states[currentKey] !in setOf(
                    ToolLedgerState.SUCCEEDED,
                    ToolLedgerState.FAILED,
                    ToolLedgerState.REJECTED,
                    ToolLedgerState.CANCELLED,
                    ToolLedgerState.TIMED_OUT,
                )
            ) return ExactToolApprovalCompletion.Conflict
            val remaining = request.calls.drop(1)
            val next = remaining.takeIf { it.isNotEmpty() }?.let {
                ToolApprovalRequestFactory.createFromIdentities(
                    assistantMessageId = request.assistantMessageId!!,
                    calls = it,
                    reason = request.reason,
                )
            }
            return ExactToolApprovalCompletion.Completed(
                com.promenar.nexara.data.repository.ApprovalTransition(
                    sessionId = sessionId,
                    currentAssistantMessageId = request.assistantMessageId!!,
                    loopStatus = when {
                        next != null -> LoopStatus.WAITING_FOR_APPROVAL
                        states[ToolExecutionKey(sessionId, request.assistantMessageId!!, request.calls.first().toolCallId)] ==
                            ToolLedgerState.REJECTED -> LoopStatus.PAUSED
                        else -> LoopStatus.RUNNING
                    },
                    approvalRequest = next,
                    nextAssistantMessageId = next?.assistantMessageId,
                    nextPendingToolCallIds = next?.calls?.map { it.toolCallId }.orEmpty(),
                ),
            )
        }
        override suspend fun compensateExactToolApproval(
            sessionId: String,
            request: ApprovalRequest,
            state: ToolLedgerState,
            error: String,
        ): ExactToolApprovalDecision {
            if (suspendCompensationCancellably) yield()
            val current = request.calls.firstOrNull() ?: return ExactToolApprovalDecision.Conflict
            val key = ToolExecutionKey(sessionId, request.assistantMessageId!!, current.toolCallId)
            if (states[key] !in setOf(ToolLedgerState.APPROVED, ToolLedgerState.RUNNING)) {
                return ExactToolApprovalDecision.Conflict
            }
            states[key] = state
            return ExactToolApprovalDecision.Decided(
                key,
                listOf(
                    Message(
                        id = "terminal-${state.name}-${key.toolCallId}",
                        role = MessageRole.TOOL,
                        toolCallId = key.toolCallId,
                        parentMessageId = key.assistantMessageId,
                        name = current.toolName,
                        content = if (state == ToolLedgerState.FAILED) "工具执行失败：$error" else "工具执行已取消。",
                    ),
                ),
            )
        }
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

    private suspend fun seedExactApproval(
        calls: List<ToolCall> = listOf(ToolCall("risk", "write_file", "{}")),
    ): ApprovalRequest {
        seedSessionWithAssistant(toolCalls = calls, pendingApprovalToolIds = calls.map { it.id })
        calls.forEach { call ->
            val identity = ToolInvocationIdentityFactory.fromLegacyToolCall(call, true)
                as ToolInvocationIdentityResolution.Valid
            ledger.register(ToolExecutionKey("s1", "m1", call.id), identity.identity)
        }
        return ToolApprovalRequestFactory.create(
            "m1",
            calls.map { ApprovalCallCandidate(it, com.promenar.nexara.domain.tool.ToolRisk.UNKNOWN) },
        ).also { approvalManager.setApprovalRequest("s1", it) }
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
        val request = seedExactApproval()

        approvalManager.resumeGeneration("s1", request, approved = false)

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
        val request = seedExactApproval()
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { sessionId, messageId, ids ->
                ids.forEach { ledger.states[ToolExecutionKey(sessionId, messageId, it)] = ToolLedgerState.SUCCEEDED }
            },
        )

        approvalManager.resumeGeneration("s1", request, approved = true)

        val session = store.getSession("s1")!!
        val lastMsg = session.messages.find { it.id == "m1" }!!
        val decisionStep = lastMsg.executionSteps?.lastOrNull()
        assertThat(decisionStep).isNotNull()
        assertThat(decisionStep!!.type).isEqualTo("intervention_result")
        assertThat(decisionStep.content).contains("Approved")
    }

    @Test
    fun resumeGenerationWithIntervention() = testScope.runTest {
        val request = seedExactApproval()
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { sessionId, messageId, ids ->
                ids.forEach { ledger.states[ToolExecutionKey(sessionId, messageId, it)] = ToolLedgerState.SUCCEEDED }
            },
        )

        approvalManager.resumeGeneration("s1", request, approved = true, intervention = "Do it differently")

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
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(ApprovalCallCandidate(calls[1], com.promenar.nexara.domain.tool.ToolRisk.FILE_WRITE)),
        )
        approvalManager.setApprovalRequest("s1", request)
        var executedIds: Set<String>? = null
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { sessionId, messageId, allowedIds ->
                executedIds = allowedIds
                allowedIds.forEach { ledger.states[ToolExecutionKey(sessionId, messageId, it)] = ToolLedgerState.SUCCEEDED }
            },
        )

        approvalManager.resumeGeneration("s1", request, approved = true)

        assertThat(executedIds).containsExactly("risky")
        assertThat(ledger.state(riskyKey)).isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(store.getSession("s1")!!.messages.first { it.id == "m1" }.pendingApprovalToolIds)
            .isNull()
    }

    @Test
    fun rejectionOnlyTerminalizesCurrentVisibleIdentityAndKeepsNextQueued() = testScope.runTest {
        val calls = listOf(
            ToolCall("r1", "write_file", "{}"),
            ToolCall("r2", "delete_file", "{}"),
        )
        seedSessionWithAssistant(toolCalls = calls, pendingApprovalToolIds = listOf("r1", "r2"))
        val keys = calls.map { ToolExecutionKey("s1", "m1", it.id) }.toSet()
        calls.zip(keys).forEach { (call, key) -> ledger.register(key, call.name, true) }
        val request = ToolApprovalRequestFactory.create(
            "m1",
            calls.map { ApprovalCallCandidate(it, com.promenar.nexara.domain.tool.ToolRisk.UNKNOWN) },
        )
        approvalManager.setApprovalRequest("s1", request)

        approvalManager.resumeGeneration("s1", request, approved = false)
        advanceUntilIdle()

        assertThat(ledger.state(keys.first())).isEqualTo(ToolLedgerState.REJECTED)
        assertThat(ledger.state(keys.last())).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
        val toolMessages = store.getSession("s1")!!.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages.mapNotNull { it.toolCallId }).containsExactly("r1")
        assertThat(toolMessages.all { it.content.contains("已拒绝") }).isTrue()
        assertThat(store.getSession("s1")!!.approvalRequest!!.calls.map { it.toolCallId })
            .containsExactly("r2")
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
    fun callbackFailureWritesDeterministicFailedToolMessageAndClosesApproval() = testScope.runTest {
        val call = ToolCall("risky", "write_file", "{}")
        seedSessionWithAssistant(
            toolCalls = listOf(call),
            pendingApprovalToolIds = listOf(call.id),
        )
        val key = ToolExecutionKey("s1", "m1", call.id)
        ledger.register(key, call.name, true)
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(ApprovalCallCandidate(call, com.promenar.nexara.domain.tool.ToolRisk.UNKNOWN)),
        )
        approvalManager.setApprovalRequest("s1", request)
        approvalManager.setLoopStatus("s1", LoopStatus.WAITING_FOR_APPROVAL)
        approvalManager.setCallbacks(
            onGenerateMessage = null,
            onExecuteTools = { _, _, _ -> throw IllegalStateException("模拟进程在claim前终止") },
        )

        approvalManager.resumeGeneration("s1", request, approved = true)

        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.FAILED)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
        assertThat(store.getSession("s1")!!.loopStatus).isEqualTo(LoopStatus.RUNNING)
        assertThat(store.getSession("s1")!!.messages.first().pendingApprovalToolIds).isNull()
        assertThat(store.getSession("s1")!!.messages.last().content).contains("工具执行失败")
        val persistedApprovalUpdates = sessionUpdates
            .filter { "approvalRequest" in it }
            .map { it["approvalRequest"] }
        assertThat(persistedApprovalUpdates).containsExactly(request)
    }

    @Test
    fun callbackExceptionAfterSucceededTerminalCompletesAndAdvancesWithoutRewritingOutcome() = testScope.runTest {
        val calls = listOf(
            ToolCall("succeeded", "write_file", "{}"),
            ToolCall("next", "delete_file", "{}"),
        )
        seedSessionWithAssistant(toolCalls = calls, pendingApprovalToolIds = calls.map { it.id })
        calls.forEach { ledger.register(ToolExecutionKey("s1", "m1", it.id), it.name, true) }
        val request = ToolApprovalRequestFactory.create(
            "m1",
            calls.map { ApprovalCallCandidate(it, com.promenar.nexara.domain.tool.ToolRisk.FILE_WRITE) },
        )
        approvalManager.setApprovalRequest("s1", request)
        val key = ToolExecutionKey("s1", "m1", calls.first().id)
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { _, _, _ ->
                ledger.states[key] = ToolLedgerState.SUCCEEDED
                throw IllegalStateException("result mirror failed")
            },
        )

        approvalManager.resumeGeneration("s1", request, approved = true)

        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(store.getSession("s1")!!.approvalRequest!!.calls.map { it.toolCallId })
            .containsExactly("next")
        assertThat(store.getSession("s1")!!.messages.first().pendingApprovalToolIds)
            .containsExactly("next")
        assertThat(store.getSession("s1")!!.messages.filter { it.role == MessageRole.TOOL }).isEmpty()
    }

    @Test
    fun callbackExceptionAfterFailedTerminalCompletesAndAdvancesWithoutRewritingOutcome() = testScope.runTest {
        val calls = listOf(
            ToolCall("failed", "write_file", "{}"),
            ToolCall("next", "delete_file", "{}"),
        )
        seedSessionWithAssistant(toolCalls = calls, pendingApprovalToolIds = calls.map { it.id })
        calls.forEach { ledger.register(ToolExecutionKey("s1", "m1", it.id), it.name, true) }
        val request = ToolApprovalRequestFactory.create(
            "m1",
            calls.map { ApprovalCallCandidate(it, com.promenar.nexara.domain.tool.ToolRisk.FILE_WRITE) },
        )
        approvalManager.setApprovalRequest("s1", request)
        val key = ToolExecutionKey("s1", "m1", calls.first().id)
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { _, _, _ ->
                ledger.states[key] = ToolLedgerState.FAILED
                throw IllegalStateException("result mirror failed")
            },
        )

        approvalManager.resumeGeneration("s1", request, approved = true)

        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.FAILED)
        assertThat(store.getSession("s1")!!.approvalRequest!!.calls.map { it.toolCallId })
            .containsExactly("next")
        assertThat(store.getSession("s1")!!.messages.first().pendingApprovalToolIds)
            .containsExactly("next")
    }

    @Test
    fun callbackCancellationCompensatesThenRethrows() = testScope.runTest {
        val call = ToolCall("cancelled", "write_file", "{}")
        seedSessionWithAssistant(toolCalls = listOf(call), pendingApprovalToolIds = listOf(call.id))
        val key = ToolExecutionKey("s1", "m1", call.id)
        ledger.register(key, call.name, true)
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(ApprovalCallCandidate(call, com.promenar.nexara.domain.tool.ToolRisk.FILE_WRITE)),
        )
        approvalManager.setApprovalRequest("s1", request)
        approvalManager.setCallbacks(
            onGenerateMessage = null,
            onExecuteTools = { _, _, _ -> throw CancellationException("cancel") },
        )

        val failure = runCatching {
            approvalManager.resumeGeneration("s1", request, approved = true)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
        assertThat(store.getSession("s1")!!.messages.last().content).contains("已取消")
    }

    @Test
    fun realJobCancellationCompletesCompensationInNonCancellableContextAndRethrows() = testScope.runTest {
        val call = ToolCall("job-cancelled", "write_file", "{}")
        seedSessionWithAssistant(toolCalls = listOf(call), pendingApprovalToolIds = listOf(call.id))
        val key = ToolExecutionKey("s1", "m1", call.id)
        ledger.register(key, call.name, true)
        ledger.suspendCompensationCancellably = true
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(ApprovalCallCandidate(call, com.promenar.nexara.domain.tool.ToolRisk.FILE_WRITE)),
        )
        approvalManager.setApprovalRequest("s1", request)
        val callbackEntered = CompletableDeferred<Unit>()
        val propagated = CompletableDeferred<Throwable>()
        approvalManager.setCallbacks(
            onGenerateMessage = null,
            onExecuteTools = { _, _, _ ->
                callbackEntered.complete(Unit)
                CompletableDeferred<Unit>().await()
            },
        )

        val job = launch {
            try {
                approvalManager.resumeGeneration("s1", request, approved = true)
            } catch (error: Throwable) {
                propagated.complete(error)
                throw error
            }
        }
        callbackEntered.await()
        job.cancel(CancellationException("real cancellation"))
        job.join()

        assertThat(propagated.await()).isInstanceOf(CancellationException::class.java)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
        assertThat(store.getSession("s1")!!.messages.last().content).contains("已取消")
    }

    @Test
    fun concurrentDoubleSubmitExecutesCurrentApprovalOnlyOnce() = testScope.runTest {
        val call = ToolCall("once", "write_file", "{}")
        seedSessionWithAssistant(toolCalls = listOf(call), pendingApprovalToolIds = listOf(call.id))
        val key = ToolExecutionKey("s1", "m1", call.id)
        ledger.register(key, call.name, true)
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(ApprovalCallCandidate(call, com.promenar.nexara.domain.tool.ToolRisk.FILE_WRITE)),
        )
        approvalManager.setApprovalRequest("s1", request)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executions = 0
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { sessionId, messageId, ids ->
                executions += 1
                entered.complete(Unit)
                release.await()
                ids.forEach { ledger.states[ToolExecutionKey(sessionId, messageId, it)] = ToolLedgerState.SUCCEEDED }
            },
        )

        val first = launch { approvalManager.resumeGeneration("s1", request, approved = true) }
        entered.await()
        val duplicate = launch { approvalManager.resumeGeneration("s1", request, approved = false) }
        release.complete(Unit)
        listOf(first, duplicate).joinAll()

        assertThat(executions).isEqualTo(1)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
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
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(ApprovalCallCandidate(first, com.promenar.nexara.domain.tool.ToolRisk.UNKNOWN)),
        )
        approvalManager.setApprovalRequest("s1", request)
        val nextRequest = ToolApprovalRequestFactory.create(
            "m2",
            listOf(ApprovalCallCandidate(next, com.promenar.nexara.domain.tool.ToolRisk.UNKNOWN)),
        )
        ledger.completionTransition = com.promenar.nexara.data.repository.ApprovalTransition(
            sessionId = "s1",
            currentAssistantMessageId = "m1",
            loopStatus = LoopStatus.WAITING_FOR_APPROVAL,
            approvalRequest = nextRequest,
            nextAssistantMessageId = "m2",
            nextPendingToolCallIds = listOf(next.id),
        )
        var generateCount = 0
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> generateCount += 1 },
            onExecuteTools = { _, _, _ -> },
        )

        approvalManager.resumeGeneration("s1", request, approved = true)

        assertThat(generateCount).isEqualTo(0)
        assertThat(store.getSession("s1")!!.loopStatus).isEqualTo(LoopStatus.WAITING_FOR_APPROVAL)
        assertThat(store.getSession("s1")!!.approvalRequest?.calls?.single()?.toolName)
            .isEqualTo(next.name)
        assertThat(store.getSession("s1")!!.messages.first { it.id == "m2" }.pendingApprovalToolIds)
            .containsExactly(next.id)
    }

    @Test
    fun consecutiveExactApprovalsAlwaysDecideTheCurrentlyVisibleFirstIdentity() = testScope.runTest {
        val calls = listOf(
            ToolCall("first", "write_file", "{\"path\":\"a\"}"),
            ToolCall("second", "delete_file", "{\"path\":\"b\"}"),
        )
        seedSessionWithAssistant(toolCalls = calls, pendingApprovalToolIds = calls.map { it.id })
        calls.forEach { call ->
            val identity = ToolInvocationIdentityFactory.fromLegacyToolCall(call, true)
                as ToolInvocationIdentityResolution.Valid
            ledger.register(ToolExecutionKey("s1", "m1", call.id), identity.identity)
        }
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(
                ApprovalCallCandidate(calls[0], com.promenar.nexara.domain.tool.ToolRisk.FILE_WRITE),
                ApprovalCallCandidate(calls[1], com.promenar.nexara.domain.tool.ToolRisk.DELETE),
            ),
        )
        approvalManager.setApprovalRequest("s1", request)
        val executed = mutableListOf<Set<String>>()
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { sessionId, messageId, ids ->
                executed += ids
                ids.forEach { ledger.states[ToolExecutionKey(sessionId, messageId, it)] = ToolLedgerState.SUCCEEDED }
            },
        )

        approvalManager.resumeGeneration("s1", request, approved = true)
        val nextRequest = store.getSession("s1")!!.approvalRequest!!
        approvalManager.resumeGeneration("s1", nextRequest, approved = true)
        approvalManager.resumeGeneration("s1", request, approved = true)

        assertThat(executed).containsExactly(setOf("first"), setOf("second")).inOrder()
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "first")))
            .isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "second")))
            .isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
    }

    @Test
    fun staleHashMessageIdAndQueueMismatchFailClosedWithoutExecution() = testScope.runTest {
        val call = ToolCall("risk", "write_file", "{\"path\":\"a\"}")
        seedSessionWithAssistant(toolCalls = listOf(call), pendingApprovalToolIds = listOf(call.id))
        ledger.register(ToolExecutionKey("s1", "m1", call.id), call.name, true)
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(ApprovalCallCandidate(call, com.promenar.nexara.domain.tool.ToolRisk.FILE_WRITE)),
        )
        approvalManager.setApprovalRequest("s1", request)
        var executionCount = 0
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { _, _, _ -> executionCount += 1 },
        )

        approvalManager.resumeGeneration("s1", request.copy(identityHash = "old"), approved = true)
        approvalManager.resumeGeneration("s1", request.copy(assistantMessageId = "old-message"), approved = true)
        approvalManager.resumeGeneration("s1", request.copy(calls = emptyList()), approved = false)

        assertThat(executionCount).isEqualTo(0)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", call.id)))
            .isEqualTo(ToolLedgerState.PENDING_APPROVAL)
        assertThat(store.getSession("s1")!!.approvalRequest).isEqualTo(request)
    }
}
