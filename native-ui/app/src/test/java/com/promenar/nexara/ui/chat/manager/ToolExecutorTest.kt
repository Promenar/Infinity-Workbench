package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.*
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.ui.chat.ChatStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolExecutorTest {
    private lateinit var store: ChatStore
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var messageManager: MessageManager
    private lateinit var sessionManager: SessionManager
    private val testScope = TestScope()

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {}
        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {}
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

    @Before
    fun setUp() {
        store = ChatStore()
        messageManager = MessageManager(store, stubMessageRepo, stubSessionRepo, testScope)
        sessionManager = SessionManager(store, stubSessionRepo)
        toolExecutor = ToolExecutor(store, messageManager, null)
    }

    private suspend fun seedSessionWithAssistant(
        sessionId: String = "s1",
        toolsEnabled: Boolean = true
    ): Session {
        val session = Session(
            id = sessionId,
            agentId = "a1",
            title = "Test",
            options = SessionOptions(toolsEnabled = toolsEnabled)
        )
        sessionManager.addSession(session)
        testScope.advanceUntilIdle()

        val msg = Message(
            id = "m1",
            role = MessageRole.ASSISTANT,
            content = "I'll use a tool",
            createdAt = 1000L
        )
        store.updateSession(sessionId) { s -> s.copy(messages = s.messages + msg) }
        return session
    }

    @Test
    fun executeToolsBlockedWhenDisabled() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = false)

        val toolCalls = listOf(ToolCall(id = "tc1", name = "read_file", arguments = """{"path":"/tmp"}"""))
        toolExecutor.executeTools("s1", toolCalls)
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].content).contains("DISABLED")
    }

    @Test
    fun executeToolsWithNoRegistry() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = true)

        val toolCalls = listOf(ToolCall(id = "tc1", name = "read_file", arguments = """{"path":"/tmp"}"""))
        toolExecutor.executeTools("s1", toolCalls)
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].content).contains("SkillRegistry not configured")
    }

    @Test
    fun executeToolsWithCustomRegistry() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = true)

        val customRegistry = object : SkillRegistry {
            override fun getSkill(name: String): SkillDefinition? {
                if (name == "read_file") {
                    return object : SkillDefinition {
                        override val id = "read_file"
                        override val name = "read_file"
                        override val description = "Read a file"
                        override val mcpServerId: String? = null
                        override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
                            return ToolResult(id = "tc1", content = "File contents here", status = "success")
                        }
                    }
                }
                return null
            }
        }

        val executorWithRegistry = ToolExecutor(store, messageManager, customRegistry)
        val toolCalls = listOf(ToolCall(id = "tc1", name = "read_file", arguments = """{"path":"/tmp"}"""))
        executorWithRegistry.executeTools("s1", toolCalls)
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].content).isEqualTo("File contents here")
    }

    @Test
    fun executeToolsSkillNotFound() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = true)

        val customRegistry = object : SkillRegistry {
            override fun getSkill(name: String) = null
        }

        val executorWithRegistry = ToolExecutor(store, messageManager, customRegistry)
        val toolCalls = listOf(ToolCall(id = "tc1", name = "unknown_tool", arguments = "{}"))
        executorWithRegistry.executeTools("s1", toolCalls)
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].content).contains("not found")
    }

    @Test
    fun executeToolsSkillThrows() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = true)

        val customRegistry = object : SkillRegistry {
            override fun getSkill(name: String): SkillDefinition {
                return object : SkillDefinition {
                    override val id = "fail_tool"
                    override val name = "fail_tool"
                    override val description = "Fails"
                    override val mcpServerId: String? = null
                    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
                        throw RuntimeException("Something went wrong")
                    }
                }
            }
        }

        val executorWithRegistry = ToolExecutor(store, messageManager, customRegistry)
        val toolCalls = listOf(ToolCall(id = "tc1", name = "fail_tool", arguments = "{}"))
        executorWithRegistry.executeTools("s1", toolCalls)
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].content).contains("Something went wrong")
    }
}
