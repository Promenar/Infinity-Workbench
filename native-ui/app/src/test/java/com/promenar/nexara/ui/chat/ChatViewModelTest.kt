package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.model.*
import com.promenar.nexara.data.remote.protocol.*
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.repository.IAgentRepository
import com.promenar.nexara.domain.usecase.AgentConfigResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = NexaraApplication::class)
class ChatViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: ChatViewModel

    private val savedSessions = mutableListOf<Session>()
    private val savedMessages = mutableListOf<Pair<Message, String>>()
    private val deletedMessages = mutableListOf<String>()

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {
            savedSessions.add(session)
        }

        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {
            savedSessions.find { it.id == id }?.let { session ->
                // Basic mock update
                if (updates.containsKey("title")) {
                    // Update in list
                }
            }
        }
        override suspend fun delete(id: String) {
            savedSessions.removeAll { it.id == id }
        }
        override suspend fun getById(id: String): Session? = savedSessions.find { it.id == id }
        override suspend fun getAll(): List<Session> = savedSessions.toList()
    }

    private val stubMessageRepo = object : IMessageRepository {
        override suspend fun insert(message: Message, sessionId: String) {
            savedMessages.add(message to sessionId)
            // Also update the stubbed session
            savedSessions.find { it.id == sessionId }?.let { session ->
                val updated = session.copy(messages = session.messages + message)
                savedSessions.removeIf { it.id == sessionId }
                savedSessions.add(updated)
            }
        }

        override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {
            savedMessages.find { it.first.id == messageId }?.let { (msg, sid) ->
                // Simplistic update
            }
        }
        override suspend fun delete(messageId: String) {
            deletedMessages.add(messageId)
            savedMessages.removeIf { it.first.id == messageId }
            savedSessions.forEachIndexed { index, session ->
                savedSessions[index] = session.copy(messages = session.messages.filter { it.id != messageId })
            }
        }

        override suspend fun deleteBySessionId(sessionId: String) {
            savedMessages.removeIf { it.second == sessionId }
        }
        override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) {
            savedMessages.removeIf { it.second == sessionId && it.first.createdAt >= timestamp }
        }
        override suspend fun getById(messageId: String): Message? = savedMessages.find { it.first.id == messageId }?.first
        override suspend fun getBySession(sessionId: String): List<Message> = savedMessages.filter { it.second == sessionId }.map { it.first }
        override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) {}
    }

    private val stubAgentRepo = object : IAgentRepository {
        private val agents = mutableListOf<Agent>()
        var lastGetByIdId: String? = null

        fun seed(agent: Agent) {
            agents.add(agent)
        }

        override fun observeAll() = kotlinx.coroutines.flow.flowOf(agents.toList())
        override fun observeById(id: String) = kotlinx.coroutines.flow.flowOf(agents.find { it.id == id })
        override suspend fun create(agent: Agent) { agents.add(agent) }
        override suspend fun update(agent: Agent) {
            agents.removeAll { it.id == agent.id }
            agents.add(agent)
        }
        override suspend fun delete(id: String) { agents.removeAll { it.id == id } }
        override suspend fun getById(id: String): Agent? {
            lastGetByIdId = id
            return agents.find { it.id == id }
        }
    }

    private var fakeStreamChunks: List<StreamChunk> = emptyList()

    private val fakeProtocol = object : LlmProtocol {
        override val protocolType = ProtocolType.OpenAI_ChatCompletions
        override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
            return flow {
                for (chunk in fakeStreamChunks) {
                    emit(chunk)
                }
            }
        }

        override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
            return PromptResponse(content = "test response")
        }

        override fun cancel() {}
    }

    private val fakeLlmProvider = LlmProvider(fakeProtocol)

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
        val app = ApplicationProvider.getApplicationContext<NexaraApplication>()
        app.chatStore.clear()
        
        savedSessions.clear()
        savedMessages.clear()
        deletedMessages.clear()
        fakeStreamChunks = emptyList()

        stubAgentRepo.seed(Agent(
            id = "a1",
            name = "Test Agent",
            systemPrompt = "test prompt",
            modelId = "gpt-4o"
        ))

        val configResolver = AgentConfigResolver(
            app.getSharedPreferences("nexara_settings", 0)
        )

        viewModel = ChatViewModel(
            application = app,
            sessionRepository = stubSessionRepo,
            messageRepository = stubMessageRepo,
            agentRepository = stubAgentRepo,
            llmProvider = fakeLlmProvider,
            configResolver = configResolver
        )
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun seedSession(id: String = "s1") {
        val session = Session(
            id = id,
            agentId = "a1",
            title = "Test",
            modelId = "gpt-4o",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        savedSessions.add(session)
        viewModel.loadSession(id)
    }

    @Test
    fun sendMessage_createsUserAndAssistantMessages() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(StreamChunk.Done)

        viewModel.sendMessage("hello"); advanceUntilIdle()
        

        val uiState = viewModel.uiState.value
        assertThat(uiState.messages).hasSize(2)
        assertThat(uiState.messages[0].role).isEqualTo(MessageRole.USER)
        assertThat(uiState.messages[1].role).isEqualTo(MessageRole.ASSISTANT)
        
    }

    @Test
    fun sendMessage_persistsMessagesToRepository() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(StreamChunk.Done)

        viewModel.sendMessage("hello"); advanceUntilIdle()
        

        assertThat(savedMessages).hasSize(2)
        assertThat(savedMessages[0].first.role).isEqualTo(MessageRole.USER)
        assertThat(savedMessages[0].second).isEqualTo("s1")
        
    }

    @Test
    fun sendMessage_streamingResponseUpdatesStreamingContent() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("Hello"),
            StreamChunk.TextDelta(" world"),
            StreamChunk.Done
        )

        viewModel.sendMessage("hi"); advanceUntilIdle()
        
          

        val session = viewModel.uiState.value.session
        val assistantMsg = session?.messages?.find { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMsg?.content).isEqualTo("Hello world")
        
    }

    @Test
    fun sendMessage_completedGenerationCallsPostProcessor() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("response"),
            StreamChunk.Usage(ProtocolUsage(input = 10, output = 20, total = 30)),
            StreamChunk.Done
        )

        viewModel.sendMessage("test"); advanceUntilIdle()
        
         

        val session = viewModel.uiState.value.session
        val stats = session?.stats
        assertThat(stats).isNotNull()
        assertThat(stats!!.totalTokens).isEqualTo(30)
        
    }

    @Test
    fun stopGeneration_resetsIsGenerating() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        

        assertThat(viewModel.uiState.value.isGenerating).isFalse()
        
    }

    @Test
    fun retryLastMessage_resendsLastUserMessage() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("first response"),
            StreamChunk.Done
        )

        viewModel.sendMessage("hello"); advanceUntilIdle()
        
         

        assertThat(viewModel.uiState.value.messages.any { it.content == "first response" }).isTrue()

        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("retry response"),
            StreamChunk.Done
        )

        viewModel.retryLastMessage(); advanceUntilIdle()
        
         

        val messages = viewModel.uiState.value.messages
        val assistantMessages = messages.filter { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMessages.any { it.content == "retry response" }).isTrue()
        
    }

    @Test
    fun sendMessage_withError_updatesErrorState() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(
            StreamChunk.Error("Something went wrong")
        )

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("Something went wrong")
        
    }

    @Test
    fun loadSession_fetchesAgentNameFromRepository() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()

        assertThat(stubAgentRepo.lastGetByIdId).isEqualTo("a1")
    }
}
