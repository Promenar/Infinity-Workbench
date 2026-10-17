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
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import com.promenar.nexara.data.remote.provider.LlmProvider
import io.mockk.coEvery
import io.mockk.mockk

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
        assertThat(updatedSession.title).isEqualTo("What is th")
    }

    @Test
    fun updateStatsWithTitleGeneratorSetsGeneratedTitle() = testScope.runTest {
        val store = ChatStore()
        val sessionManager = SessionManager(store, stubSessionRepo)
        val messageManager = MessageManager(store, stubMessageRepo, stubSessionRepo, testScope)
        val postProcessor = PostProcessor(store, sessionManager, messageManager).apply {
            setTitleGenerator { "天气查询" }
        }

        val agent = Agent(id = "a1", name = "Test Agent")
        val session = Session(id = "s1", agentId = "a1", title = "New Chat")
        sessionManager.addSession(session)
        testScope.advanceUntilIdle()

        val params = PostProcessorParams(
            sessionId = "s1",
            assistantMsgId = "m2",
            userMsgId = "m1",
            userContent = "今天北京天气怎么样？",
            assistantContent = "今天北京晴转多云。",
            agent = agent,
            session = store.getSession("s1")!!,
            ragEnabled = false,
            modelId = "gpt-4o"
        )

        postProcessor.updateStats(params)
        testScope.advanceUntilIdle()

        val updatedSession = store.getSession("s1")!!
        assertThat(updatedSession.title).isEqualTo("天气查询")
    }

    @Test
    fun cleanTitleSanitizesPunctuationAndLimitsLength() {
        assertThat(PostProcessor.cleanTitle("《超级人工智能大模型深度思考与解析指南》")).isEqualTo("超级人工智能大模型深")
        assertThat(PostProcessor.cleanTitle("“今日北京天气：多云”")).isEqualTo("今日北京天气多云")
        assertThat(PostProcessor.cleanTitle("  ### 标题：量子力学浅析！  ")).isEqualTo("量子力学浅析")
        assertThat(PostProcessor.cleanTitle("")).isNull()
    }

    @Test
    fun `标题优先使用快速模型而非当前会话模型`() = runTest {
        val calls = mutableListOf<String>()
        val provider = mockk<LlmProvider>()
        coEvery { provider.sendPromptSync(any()) } answers {
            calls += firstArg<PromptRequest>().model
            PromptResponse("快速标题")
        }

        val title = PostProcessor.generateTitleWithFallback(titleParams(), "quick::model", provider)

        assertThat(calls).containsExactly("quick::model")
        assertThat(title).isEqualTo("快速标题")
    }

    @Test
    fun `快速模型无标题时沿用会话模型回退`() = runTest {
        val calls = mutableListOf<String>()
        val provider = mockk<LlmProvider>()
        coEvery { provider.sendPromptSync(any()) } answers {
            val model = firstArg<PromptRequest>().model
            calls += model
            PromptResponse(if (model == "quick::model") "" else "天气分析")
        }

        val title = PostProcessor.generateTitleWithFallback(titleParams(), "quick::model", provider)

        assertThat(calls).containsExactly("quick::model", "current::model").inOrder()
        assertThat(title).isEqualTo("天气分析")
    }

    private fun titleParams() = PostProcessorParams(
        sessionId = "s1", assistantMsgId = "a1", userMsgId = "u1",
        userContent = "需要一个标题", assistantContent = "完整回答",
        agent = Agent("agent", "智能体"), session = Session("s1", "agent", modelId = "current::model"),
        ragEnabled = false, modelId = "current::model",
    )
}
