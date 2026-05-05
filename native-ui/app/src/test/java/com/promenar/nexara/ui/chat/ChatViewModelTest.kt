package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.*
import com.promenar.nexara.data.remote.protocol.*
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var viewModel: ChatViewModel

    private val savedSessions = mutableListOf<Session>()
    private val savedMessages = mutableListOf<Pair<Message, String>>()
    private val deletedMessages = mutableListOf<String>()

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {
            savedSessions.add(session)
        }

        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {}
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): Session? = savedSessions.find { it.id == id }
        override suspend fun getAll(): List<Session> = savedSessions.toList()
    }

    private val stubMessageRepo = object : IMessageRepository {
        override suspend fun insert(message: Message, sessionId: String) {
            savedMessages.add(message to sessionId)
        }

        override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {}
        override suspend fun delete(messageId: String) {
            deletedMessages.add(messageId)
        }

        override suspend fun deleteBySessionId(sessionId: String) {}
        override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) {}
        override suspend fun getById(messageId: String): Message? = null
        override suspend fun getBySession(sessionId: String): List<Message> = emptyList()
        override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) {}
    }

    private var fakeStreamChunks: List<StreamChunk> = emptyList()

    private val fakeProtocol = object : LlmProtocol {
        override val id: ProtocolId = ProtocolId.OPENAI
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
        savedSessions.clear()
        savedMessages.clear()
        deletedMessages.clear()
        fakeStreamChunks = emptyList()
        viewModel = ChatViewModel(
            sessionRepository = stubSessionRepo,
            messageRepository = stubMessageRepo,
            llmProvider = fakeLlmProvider
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
        testScope.advanceUntilIdle()
    }

    @Test
    fun sendMessage_createsUserAndAssistantMessages() = testScope.runTest {
        seedSession()
        fakeStreamChunks = listOf(StreamChunk.Done)

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertThat(uiState.messages).hasSize(2)
        assertThat(uiState.messages[0].role).isEqualTo(MessageRole.USER)
        assertThat(uiState.messages[0].content).isEqualTo("hello")
        assertThat(uiState.messages[1].role).isEqualTo(MessageRole.ASSISTANT)
    }

    @Test
    fun sendMessage_persistsMessagesToRepository() = testScope.runTest {
        seedSession()
        fakeStreamChunks = listOf(StreamChunk.Done)

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        assertThat(savedMessages).hasSize(2)
        assertThat(savedMessages[0].first.role).isEqualTo(MessageRole.USER)
        assertThat(savedMessages[0].first.content).isEqualTo("hello")
        assertThat(savedMessages[0].second).isEqualTo("s1")
    }

    @Test
    fun sendMessage_streamingResponseUpdatesStreamingContent() = testScope.runTest {
        seedSession()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("Hello"),
            StreamChunk.TextDelta(" world"),
            StreamChunk.Done
        )

        viewModel.sendMessage("hi")
        advanceUntilIdle()

        val session = viewModel.uiState.value.session
        val assistantMsg = session?.messages?.find { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMsg?.content).isEqualTo("Hello world")
    }

    @Test
    fun sendMessage_completedGenerationCallsPostProcessor() = testScope.runTest {
        seedSession()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("response"),
            StreamChunk.Usage(ProtocolUsage(input = 10, output = 20, total = 30)),
            StreamChunk.Done
        )

        viewModel.sendMessage("test")
        advanceUntilIdle()

        val session = viewModel.uiState.value.session
        val stats = session?.stats
        assertThat(stats).isNotNull()
        assertThat(stats!!.totalTokens).isEqualTo(30)
    }

    @Test
    fun stopGeneration_resetsIsGenerating() = testScope.runTest {
        seedSession()

        assertThat(viewModel.uiState.value.isGenerating).isFalse()
    }

    @Test
    fun retryLastMessage_resendsLastUserMessage() = testScope.runTest {
        seedSession()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("first response"),
            StreamChunk.Done
        )

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.messages.any { it.content == "first response" }).isTrue()

        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("retry response"),
            StreamChunk.Done
        )

        viewModel.retryLastMessage()
        advanceUntilIdle()

        val messages = viewModel.uiState.value.messages
        val assistantMessages = messages.filter { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMessages.any { it.content == "retry response" }).isTrue()
    }

    @Test
    fun sendMessage_withError_updatesErrorState() = testScope.runTest {
        seedSession()
        fakeStreamChunks = listOf(
            StreamChunk.Error("Something went wrong")
        )

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("Something went wrong")
    }
}
