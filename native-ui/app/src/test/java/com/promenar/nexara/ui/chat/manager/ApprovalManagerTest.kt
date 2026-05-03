package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.*
import com.promenar.nexara.data.repository.ISessionRepository
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
    private val testScope = TestScope()

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {}
        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {}
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): Session? = null
        override suspend fun getAll(): List<Session> = emptyList()
    }

    @Before
    fun setUp() {
        store = ChatStore()
        sessionManager = SessionManager(store, stubSessionRepo)
        approvalManager = ApprovalManager(store)
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
}
