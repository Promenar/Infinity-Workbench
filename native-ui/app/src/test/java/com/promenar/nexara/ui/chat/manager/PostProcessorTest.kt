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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostProcessorTest {
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

    @Test
    fun estimateTokens() {
        assertThat(PostProcessor.estimateTokens("")).isEqualTo(0)
        assertThat(PostProcessor.estimateTokens("a")).isEqualTo(1)
        assertThat(PostProcessor.estimateTokens("hello world")).isEqualTo(2)
        assertThat(PostProcessor.estimateTokens("a very long string")).isEqualTo(4)
    }

    @Test
    fun updateStatsSetsBilling() = testScope.runTest {
        val store = ChatStore()
        val sessionManager = SessionManager(store, stubSessionRepo)
        val messageManager = MessageManager(store, stubMessageRepo, stubSessionRepo, testScope)
        val postProcessor = PostProcessor(store, sessionManager, messageManager)

        val agent = Agent(id = "a1", name = "Test Agent")
        val session = Session(id = "s1", agentId = "a1", title = "New Chat")
        sessionManager.addSession(session)
        testScope.advanceUntilIdle()

        val params = PostProcessorParams(
            sessionId = "s1",
            assistantMsgId = "m2",
            userMsgId = "m1",
            userContent = "Hello, how are you doing today?",
            assistantContent = "I'm doing great, thanks for asking!",
            agent = agent,
            session = store.getSession("s1")!!,
            ragEnabled = false,
            accumulatedUsage = TokenUsage(input = 50, output = 30, total = 80),
            modelId = "gpt-4o"
        )

        postProcessor.updateStats(params)
        testScope.advanceUntilIdle()

        val updatedSession = store.getSession("s1")!!
        assertThat(updatedSession.stats).isNotNull()
        assertThat(updatedSession.stats!!.billing.chatInput.count).isEqualTo(50)
        assertThat(updatedSession.stats!!.billing.chatOutput.count).isEqualTo(30)
        assertThat(updatedSession.stats!!.billing.total).isEqualTo(80)
    }

    @Test
    fun updateStatsAutoGeneratesTitle() = testScope.runTest {
        val store = ChatStore()
        val sessionManager = SessionManager(store, stubSessionRepo)
        val messageManager = MessageManager(store, stubMessageRepo, stubSessionRepo, testScope)
        val postProcessor = PostProcessor(store, sessionManager, messageManager)

        val agent = Agent(id = "a1", name = "Test Agent")
        val session = Session(id = "s1", agentId = "a1", title = "New Chat")
        sessionManager.addSession(session)
        testScope.advanceUntilIdle()

        val params = PostProcessorParams(
            sessionId = "s1",
            assistantMsgId = "m2",
            userMsgId = "m1",
            userContent = "What is the weather?",
            assistantContent = "The weather is sunny.",
            agent = agent,
            session = store.getSession("s1")!!,
            ragEnabled = false,
            modelId = "gpt-4o"
        )

        postProcessor.updateStats(params)
        testScope.advanceUntilIdle()

        val updatedSession = store.getSession("s1")!!
        assertThat(updatedSession.title).isEqualTo("What is the wea...")
    }

    @Test
    fun updateStatsSkipsTitleForSuperAssistant() = testScope.runTest {
        val store = ChatStore()
        val sessionManager = SessionManager(store, stubSessionRepo)
        val messageManager = MessageManager(store, stubMessageRepo, stubSessionRepo, testScope)
        val postProcessor = PostProcessor(store, sessionManager, messageManager)

        val agent = Agent(id = "a1", name = "Test Agent")
        val session = Session(id = "super_assistant", agentId = "a1", title = "New Chat")
        sessionManager.addSession(session)
        testScope.advanceUntilIdle()

        val params = PostProcessorParams(
            sessionId = "super_assistant",
            assistantMsgId = "m2",
            userMsgId = "m1",
            userContent = "What is the weather?",
            assistantContent = "The weather is sunny.",
            agent = agent,
            session = store.getSession("super_assistant")!!,
            ragEnabled = false,
            modelId = "gpt-4o"
        )

        postProcessor.updateStats(params)
        testScope.advanceUntilIdle()

        val updatedSession = store.getSession("super_assistant")!!
        assertThat(updatedSession.title).isEqualTo("New Chat")
    }
}
