package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.SessionOptions
import com.promenar.nexara.data.remote.DefaultProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ResolvedProviderModel
import com.promenar.nexara.data.remote.UnifiedProviderConfig
import com.promenar.nexara.data.remote.UnifiedLlmClient
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.repository.ToolExecutionLedger
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationRuntimePolicy
import com.promenar.nexara.domain.generation.GenerationToolCall
import com.promenar.nexara.domain.generation.GenerationToolDecision
import com.promenar.nexara.domain.repository.IAgentRepository
import com.promenar.nexara.domain.usecase.AgentConfigResolver
import com.promenar.nexara.ui.chat.ChatState
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.ContextBuilder
import com.promenar.nexara.ui.chat.manager.MessageManager
import com.promenar.nexara.ui.chat.manager.PostProcessor
import com.promenar.nexara.ui.chat.manager.SessionManager
import com.promenar.nexara.ui.chat.manager.SummaryManager
import com.promenar.nexara.ui.chat.manager.ToolExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException
import org.junit.Test

class DefaultChatGenerationRuntimeTest {
    @Test
    fun `Context构建取消必须上抛且不得写业务error终态`() = runTest {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } returns emptySet()
        every { settings.getString(any(), any()) } returns ""
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "s-cancel",
                            agentId = "agent",
                            modelId = "local::model",
                            messages = listOf(Message("a-cancel", MessageRole.ASSISTANT, "")),
                        ),
                    ),
                )
            }
        }
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(
                    modelId,
                    "model",
                    "local",
                    "Local",
                    UnifiedProviderConfig(ProtocolType.Local, "", "", "model"),
                ),
            )
            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("local")
        }
        val contextBuilder = mockk<ContextBuilder>()
        val cancellation = CancellationException("context cancelled")
        coEvery { contextBuilder.buildContext(any()) } throws cancellation
        val messageManager = mockk<MessageManager>(relaxed = true)
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = contextBuilder,
            messageManager = messageManager,
            localProviderFactory = { mockk() },
            provider = mockk(relaxed = true),
            toolLedger = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true),
            contentStrategy = mockk(relaxed = true),
            ui = mockk(relaxed = true),
        )
        val request = GenerationRequest(
            "s-cancel", "a-cancel", "u", "question", emptyList(),
            GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )
        runtime.prepare(request)

        val failure = runCatching { runtime.buildContext(request) }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancellation)
        coVerify(exactly = 0) {
            messageManager.markGenerationTerminal(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `无审批工具由runtime执行并返回CONTINUE而非递归生成`() = runTest {
        val settings = mockk<SharedPreferences>()
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val store = ChatStore().apply {
            update { ChatState(sessions = listOf(Session(id = "s1", agentId = "agent", executionMode = "auto"))) }
        }
        val contentStrategy = mockk<ChatGenerationContentStrategy>(relaxed = true)
        every { contentStrategy.pendingApprovalIds(any(), any()) } returns emptyList()
        val toolExecutor = mockk<ToolExecutor>(relaxed = true)
        coEvery { toolExecutor.executeTools(any(), any(), any(), any()) } returns Unit
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository>(),
            configResolver = mockk<AgentConfigResolver>(),
            routeGate = mockk<ChatProviderRouteGate>(),
            contextBuilder = mockk<ContextBuilder>(),
            messageManager = mockk<MessageManager>(relaxed = true),
            localProviderFactory = { mockk<LlmProvider>() },
            provider = mockk<LlmProvider>(relaxed = true),
            toolLedger = mockk<ToolExecutionLedger>(),
            toolExecutor = toolExecutor,
            postProcessor = mockk<PostProcessor>(),
            memoryManager = null,
            summaryManager = mockk<SummaryManager>(),
            sessionManager = mockk<SessionManager>(),
            contentStrategy = contentStrategy,
            ui = mockk<GenerationUiPort>(relaxed = true),
        )
        val request = GenerationRequest(
            sessionId = "s1",
            assistantMessageId = "a1",
            userMessageId = "u1",
            userContent = "hello",
            imageDataUrls = emptyList(),
            runtimePolicy = GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )
        val calls = listOf(GenerationToolCall("tool-1", "search", "{}"))

        val decision = runtime.handleTools(request, calls)

        assertThat(decision).isEqualTo(GenerationToolDecision.CONTINUE)
        coVerify(exactly = 1) {
            toolExecutor.executeTools(
                "s1",
                "a1",
                match { it.single().id == "tool-1" },
                match { it == setOf("tool-1") },
            )
        }
    }

    @Test
    fun `CONTINUE强刷assistant后多轮Prompt保留全部工具配对`() = runTest {
        val settings = mockk<SharedPreferences>()
        every { settings.getInt(any(), any()) } answers { secondArg() }
        every { settings.getStringSet(any(), any()) } returns emptySet()
        every { settings.getString(any(), any()) } returns ""
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        val user = Message("u1", MessageRole.USER, "question")
        val initialAssistant = Message("a0", MessageRole.ASSISTANT, "")
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "s1",
                            agentId = "agent",
                            modelId = "local::model",
                            executionMode = "auto",
                            options = SessionOptions(toolsEnabled = true),
                            messages = listOf(user, initialAssistant),
                        ),
                    ),
                )
            }
        }
        val messageRepository = object : IMessageRepository {
            override suspend fun insert(message: Message, sessionId: String) = Unit
            override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) = Unit
            override suspend fun delete(messageId: String) = Unit
            override suspend fun deleteBySessionId(sessionId: String) = Unit
            override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) = Unit
            override suspend fun getById(messageId: String): Message? = null
            override suspend fun getBySession(sessionId: String): List<Message> = emptyList()
            override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) = Unit
        }
        val sessionRepository = object : ISessionRepository {
            override suspend fun create(session: Session) = Unit
            override suspend fun updatePartial(id: String, updates: Map<String, Any?>) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun getById(id: String): Session? = null
            override suspend fun getAll(): List<Session> = emptyList()
        }
        val messageManager = MessageManager(store, messageRepository, sessionRepository, this)
        val prompts = mutableListOf<PromptRequest>()
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.Local
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
                prompts += request
                when (prompts.size) {
                    1 -> emit(StreamChunk.ToolCallDelta("call-1", "search", "{}", 0))
                    2 -> emit(StreamChunk.ToolCallDelta("call-2", "read_file", "{}", 0))
                    else -> emit(StreamChunk.TextDelta("final"))
                }
                emit(StreamChunk.Done)
            }
            override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
            override fun cancel() = Unit
        }
        val provider = LlmProvider(protocol)
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(
                    modelId,
                    "model",
                    "local",
                    "Local",
                    UnifiedProviderConfig(ProtocolType.Local, "", "", "model"),
                ),
            )
            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("local")
        }
        val toolExecutor = mockk<ToolExecutor>()
        coEvery { toolExecutor.executeTools(any(), any(), any(), any()) } coAnswers {
            val sessionId = firstArg<String>()
            val calls = thirdArg<List<com.promenar.nexara.data.model.ToolCall>>()
            calls.forEach { call ->
                messageManager.addMessage(
                    sessionId,
                    Message(
                        id = "tool-${call.id}",
                        role = MessageRole.TOOL,
                        content = "result-${call.id}",
                        toolCallId = call.id,
                        name = call.name,
                    ),
                )
            }
        }
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = ContextBuilder(),
            messageManager = messageManager,
            localProviderFactory = { provider },
            provider = provider,
            toolLedger = mockk(relaxed = true),
            toolExecutor = toolExecutor,
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = SessionManager(store, sessionRepository),
            contentStrategy = DefaultChatGenerationContentStrategy(settings, null),
            ui = mockk(relaxed = true),
        )
        val request = GenerationRequest(
            "s1", "a0", "u1", "question", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )

        ChatGenerationRunner(runtime).run(request) {}

        assertThat(prompts).hasSize(3)
        val third = prompts[2].messages
        assertThat(third.filter { it.role == "assistant" }.flatMap { it.toolCalls.orEmpty() }.map { it.id })
            .containsExactly("call-1", "call-2").inOrder()
        assertThat(third.filter { it.role == "tool" }.map { it.toolCallId })
            .containsExactly("call-1", "call-2").inOrder()
    }
}
