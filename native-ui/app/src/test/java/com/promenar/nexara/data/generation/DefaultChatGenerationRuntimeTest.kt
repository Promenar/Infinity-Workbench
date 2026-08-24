package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.SessionOptions
import com.promenar.nexara.data.model.InferenceParams
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.PostProcessStatus
import com.promenar.nexara.data.model.UpdateMessageOptions
import com.promenar.nexara.data.model.MessageDocumentAttachment
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
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.rag.MemoryManager
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.repository.ToolExecutionLedger
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationChunk
import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationFailureCode
import com.promenar.nexara.domain.generation.GenerationFailureCodec
import com.promenar.nexara.domain.generation.GenerationRuntimePolicy
import com.promenar.nexara.domain.generation.GenerationPreparationOutcome
import com.promenar.nexara.domain.generation.GenerationSnapshot
import com.promenar.nexara.domain.generation.GenerationTerminalStatus
import com.promenar.nexara.domain.generation.GenerationToolCall
import com.promenar.nexara.domain.generation.GenerationToolDecision
import com.promenar.nexara.domain.repository.IAgentRepository
import com.promenar.nexara.domain.usecase.AgentConfigResolver
import com.promenar.nexara.ui.chat.ChatState
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.ContextBuilder
import com.promenar.nexara.ui.chat.manager.ContextBuilderResult
import com.promenar.nexara.ui.chat.manager.MessageManager
import com.promenar.nexara.ui.chat.manager.PostProcessor
import com.promenar.nexara.ui.chat.manager.SessionManager
import com.promenar.nexara.ui.chat.manager.SummaryManager
import com.promenar.nexara.ui.chat.manager.ToolExecutor
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CancellationException
import org.junit.Test

class DefaultChatGenerationRuntimeTest {
    @Test
    fun `初始完整文档最终门禁失败回滚本轮消息且不进入Provider`() = runTest {
        val fixture = documentBudgetFixture(toolRounds = 0)
        val request = fixture.request.copy(rollbackUserOnPreparationFailure = true)

        fixture.runtime.prepare(request)
        val outcome = fixture.runtime.buildContext(request)

        assertThat(outcome).isInstanceOf(GenerationPreparationOutcome.Handled::class.java)
        assertThat((outcome as GenerationPreparationOutcome.Handled).failure.code)
            .isEqualTo(GenerationFailureCode.CONTEXT_LIMIT)
        coVerify(exactly = 1) {
            fixture.messageManager.discardPreparedMessages("budget-session", listOf("assistant", "user"))
        }
        assertThat(fixture.providerEntered()).isFalse()
    }

    @Test
    fun `工具续轮最终门禁失败不删除既有用户或工具历史`() = runTest {
        val fixture = documentBudgetFixture(toolRounds = 1)

        fixture.runtime.prepare(fixture.request)
        val outcome = fixture.runtime.buildContext(fixture.request)

        assertThat(outcome).isInstanceOf(GenerationPreparationOutcome.Handled::class.java)
        coVerify(exactly = 0) { fixture.messageManager.discardPreparedMessages(any(), any()) }
        assertThat(fixture.providerEntered()).isFalse()
    }

    private data class DocumentBudgetFixture(
        val runtime: DefaultChatGenerationRuntime,
        val request: GenerationRequest,
        val messageManager: MessageManager,
        val providerEntered: () -> Boolean,
    )

    private fun kotlinx.coroutines.test.TestScope.documentBudgetFixture(
        toolRounds: Int,
    ): DocumentBudgetFixture {
        val settings = mockk<SharedPreferences>()
        every { settings.getInt(any(), any()) } returns 100
        every { settings.getStringSet(any(), any()) } returns emptySet()
        every { settings.getString(any(), any()) } returns ""
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        val document = MessageDocumentAttachment(
            "doc", "large.txt", "text/plain", "中".repeat(100), 300, "hash", 300,
        )
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "budget-session",
                            agentId = "agent",
                            modelId = "local::model",
                            inferenceParams = InferenceParams(maxTokens = 16),
                            messages = listOf(
                                Message("user", MessageRole.USER, "question", userDocuments = listOf(document)),
                                Message("assistant", MessageRole.ASSISTANT, ""),
                            ),
                        ),
                    ),
                )
            }
        }
        var providerEntered = false
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.Local
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
                providerEntered = true
                return flow {
                    emit(StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN))
                }
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
        val contextBuilder = mockk<ContextBuilder>()
        coEvery { contextBuilder.buildContext(any()) } returns ContextBuilderResult(
            searchContext = "",
            ragContext = "",
            citations = emptyList(),
            ragReferences = emptyList(),
            ragUsage = null,
            finalSystemPrompt = "system",
        )
        val contentStrategy = mockk<ChatGenerationContentStrategy>()
        every { contentStrategy.buildTools(any()) } returns emptyList()
        every { contentStrategy.buildProtocolMessages(any(), any(), any(), any()) } returns listOf(
            com.promenar.nexara.data.remote.protocol.ProtocolMessage(
                role = "user",
                content = "中".repeat(100),
            ),
        )
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
            localProviderFactory = { provider },
            provider = provider,
            toolLedger = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true),
            contentStrategy = contentStrategy,
            ui = mockk(relaxed = true),
        )
        DefaultChatGenerationRuntime::class.java.getDeclaredField("toolRounds").apply {
            isAccessible = true
            setInt(runtime, toolRounds)
        }
        return DocumentBudgetFixture(
            runtime,
            GenerationRequest(
                "budget-session",
                "assistant",
                "user",
                "question",
                emptyList(),
                GenerationRuntimePolicy.BACKGROUND_ALLOWED,
            ),
            messageManager,
            { providerEntered },
        )
    }

    @Test
    fun `postProcess updateStats取消必须传播`() = runTest {
        val fixture = postProcessFixture()
        val cancellation = CancellationException("stats-cancel")
        coEvery { fixture.postProcessor.updateStats(any()) } throws cancellation

        val result = runCatching {
            fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))
        }.exceptionOrNull()

        assertThat(result).isSameInstanceAs(cancellation)
    }

    @Test
    fun `postProcess addTurnToMemory取消必须传播`() = runTest {
        val memory = mockk<MemoryManager>()
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            memoryManager = memory,
        )
        val cancellation = CancellationException("memory-cancel")
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery { memory.addTurnToMemory(any(), any(), any(), any(), any()) } throws cancellation

        val result = runCatching {
            fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))
        }.exceptionOrNull()

        assertThat(result).isSameInstanceAs(cancellation)
    }

    @Test
    fun `postProcess archiveMessagesToRag取消必须传播`() = runTest {
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            inferenceParams = InferenceParams(activeContextWindow = 1),
            messageCount = 3,
        )
        val cancellation = CancellationException("archive-cancel")
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery {
            fixture.postProcessor.archiveMessagesToRag(any(), any(), any(), any())
        } throws cancellation

        val result = runCatching {
            fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))
        }.exceptionOrNull()

        assertThat(result).isSameInstanceAs(cancellation)
        verify(exactly = 0) {
            fixture.ui.updatePostProcessTask(any(), PostProcessStatus.ERROR, any(), any())
        }
    }

    @Test
    fun `postProcess summarize取消必须传播`() = runTest {
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = false, enableDocs = false),
            inferenceParams = InferenceParams(activeContextWindow = 1, autoSummaryThreshold = 0.0),
            messageCount = 3,
        )
        val cancellation = CancellationException("summary-cancel")
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery { fixture.summaryManager.summarize(any(), any(), any(), any(), any()) } throws cancellation

        val result = runCatching {
            fixture.runtime.postProcess(
                fixture.request,
                GenerationSnapshot(content = "answer", totalTokens = 1),
            )
        }.exceptionOrNull()

        assertThat(result).isSameInstanceAs(cancellation)
        verify(exactly = 0) {
            fixture.ui.updatePostProcessTask(any(), PostProcessStatus.ERROR, any(), any())
        }
    }

    @Test
    fun `postProcess普通归档失败不得把异常原文写入任务detail`() = runTest {
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            inferenceParams = InferenceParams(activeContextWindow = 1),
            messageCount = 3,
        )
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery {
            fixture.postProcessor.archiveMessagesToRag(any(), any(), any(), any())
        } throws IllegalStateException("private-provider-marker")

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        val detail = slot<String?>()
        verify {
            fixture.ui.updatePostProcessTask(
                any(),
                PostProcessStatus.ERROR,
                any(),
                captureNullable(detail),
            )
        }
        assertThat(detail.captured).isNull()
    }

    @Test
    fun `文本 fallback 只清理展示标记不得升级为可执行工具调用`() = runTest {
        val fixture = postProcessFixture()
        every { fixture.contentStrategy.extractFallbackToolCalls("wrapped") } returns listOf(
            com.promenar.nexara.data.model.ToolCall("fallback", "search", "{}"),
        )
        every { fixture.contentStrategy.stripToolCallMarkup("wrapped") } returns "clean"

        val result = fixture.runtime.finalizeStream(
            fixture.request,
            GenerationSnapshot(content = "wrapped"),
        )

        assertThat(result.content).isEqualTo("clean")
        assertThat(result.toolCalls).isEmpty()
    }

    @Test
    fun `重试多轮工具调用失败会回滚本轮创建的全部消息`() = runTest {
        val fixture = postProcessFixture()
        val request = fixture.request.copy(assistantMessageIdToReplace = "old-assistant")
        fixture.store.update {
            ChatState(
                sessions = listOf(
                    Session(
                        id = "session",
                        agentId = "agent",
                        modelId = "model",
                        messages = listOf(
                            Message("user", MessageRole.USER, "question"),
                            Message("old-assistant", MessageRole.ASSISTANT, "old answer"),
                            Message("assistant", MessageRole.ASSISTANT, ""),
                        ),
                    ),
                ),
            )
        }
        fixture.runtime.prepare(request)
        fixture.store.update { state ->
            state.copy(
                sessions = state.sessions.map { session ->
                    session.copy(
                        messages = session.messages + listOf(
                            Message("assistant-round-2", MessageRole.ASSISTANT, "tool call 1"),
                            Message("tool-result-1", MessageRole.TOOL, "result 1"),
                            Message("assistant-round-3", MessageRole.ASSISTANT, "tool call 2"),
                            Message("tool-result-2", MessageRole.TOOL, "result 2"),
                        ),
                    )
                },
            )
        }

        fixture.runtime.markTerminal(
            request,
            GenerationTerminalStatus.ERROR,
            GenerationSnapshot(content = "partial"),
            IllegalStateException("failed"),
        )

        val rolledBackIds = slot<List<String>>()
        coVerify(exactly = 1) {
            fixture.messageManager.discardPreparedMessages("session", capture(rolledBackIds))
        }
        assertThat(rolledBackIds.captured).containsExactly(
            "assistant",
            "assistant-round-2",
            "tool-result-1",
            "assistant-round-3",
            "tool-result-2",
        )
        assertThat(rolledBackIds.captured).doesNotContain("old-assistant")
    }

    private data class PostProcessFixture(
        val runtime: DefaultChatGenerationRuntime,
        val request: GenerationRequest,
        val postProcessor: PostProcessor,
        val summaryManager: SummaryManager,
        val ui: GenerationUiPort,
        val store: ChatStore,
        val messageManager: MessageManager,
        val contentStrategy: ChatGenerationContentStrategy,
    )

    private fun postProcessFixture(
        ragOptions: RagOptions? = null,
        inferenceParams: InferenceParams? = null,
        messageCount: Int = 1,
        memoryManager: MemoryManager? = null,
    ): PostProcessFixture {
        val messages = (0 until messageCount).map { index ->
            Message(
                id = if (index == messageCount - 1) "assistant" else "message-$index",
                role = if (index == messageCount - 1) MessageRole.ASSISTANT else MessageRole.USER,
                content = "content-$index",
            )
        }
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "session",
                            agentId = "agent",
                            modelId = "model",
                            messages = messages,
                            ragOptions = ragOptions,
                            inferenceParams = inferenceParams,
                        ),
                    ),
                )
            }
        }
        val postProcessor = mockk<PostProcessor>()
        val summaryManager = mockk<SummaryManager>()
        val ui = mockk<GenerationUiPort>(relaxed = true)
        val messageManager = mockk<MessageManager>(relaxed = true)
        every { ui.addPostProcessTask(any(), any()) } returns "task"
        val contentStrategy = mockk<ChatGenerationContentStrategy>()
        every { contentStrategy.safeActiveWindow(any(), any()) } answers {
            firstArg<List<Message>>().takeLast(secondArg())
        }
        val runtime = DefaultChatGenerationRuntime(
            settings = mockk(relaxed = true),
            applicationScope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            store = store,
            agentRepository = mockk(),
            configResolver = mockk(),
            routeGate = mockk(),
            contextBuilder = mockk(),
            messageManager = messageManager,
            localProviderFactory = { mockk() },
            provider = mockk(relaxed = true),
            toolLedger = mockk(),
            toolExecutor = mockk(),
            postProcessor = postProcessor,
            memoryManager = memoryManager,
            summaryManager = summaryManager,
            sessionManager = mockk(relaxed = true),
            contentStrategy = contentStrategy,
            ui = ui,
        )
        DefaultChatGenerationRuntime::class.java.getDeclaredField("preparedContext").apply {
            isAccessible = true
            set(
                runtime,
                ContextBuilderResult(
                    searchContext = "",
                    ragContext = "",
                    citations = emptyList(),
                    ragReferences = emptyList(),
                    ragUsage = null,
                    finalSystemPrompt = "system",
                ),
            )
        }
        return PostProcessFixture(
            runtime,
            GenerationRequest(
                "session", "assistant", "user", "question", emptyList(),
                GenerationRuntimePolicy.BACKGROUND_ALLOWED,
            ),
            postProcessor,
            summaryManager,
            ui,
            store,
            messageManager,
            contentStrategy,
        )
    }
    @Test
    fun `快照与终态持久化只写失败codec安全信封`() = runTest {
        val messageManager = mockk<MessageManager>(relaxed = true)
        val ui = mockk<GenerationUiPort>(relaxed = true)
        val runtime = DefaultChatGenerationRuntime(
            settings = mockk(relaxed = true),
            applicationScope = this,
            store = ChatStore(),
            agentRepository = mockk(),
            configResolver = mockk(),
            routeGate = mockk(),
            contextBuilder = mockk(),
            messageManager = messageManager,
            localProviderFactory = { mockk() },
            provider = mockk(relaxed = true),
            toolLedger = mockk(),
            toolExecutor = mockk(),
            postProcessor = mockk(),
            memoryManager = null,
            summaryManager = mockk(),
            sessionManager = mockk(),
            contentStrategy = mockk(),
            ui = ui,
        )
        val request = GenerationRequest(
            "s-safe", "a-safe", "u-safe", "question", emptyList(),
            GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )
        val cause = IllegalStateException("provider 原始失败正文")
        val failure = GenerationFailure(
            code = GenerationFailureCode.RATE_LIMIT,
            formatArgs = mapOf(GenerationFailure.KEY_RETRY_AFTER_SECONDS to "37"),
            technical = "HTTP 429 provider 原始失败正文",
            cause = cause,
        )
        val snapshot = GenerationSnapshot(content = "partial", failure = failure)
        val options = slot<UpdateMessageOptions>()
        val terminalError = slot<String?>()

        runtime.persist(request, snapshot)
        runtime.markTerminal(request, GenerationTerminalStatus.ERROR, snapshot, cause)

        val envelope = GenerationFailureCodec.encode(failure)
        verify(exactly = 1) {
            messageManager.updateMessageContent("s-safe", "a-safe", "partial", capture(options))
        }
        assertThat(options.captured.errorMessage).isEqualTo(envelope)
        assertThat(options.captured.errorMessage).doesNotContain("provider")
        assertThat(options.captured.isError).isTrue()
        assertThat(options.captured.clearToolCalls).isTrue()
        verify(exactly = 1) { ui.setError(failure) }
        coVerify(exactly = 1) {
            messageManager.markGenerationTerminal(
                "s-safe",
                "a-safe",
                "partial",
                "error",
                captureNullable(terminalError),
            )
        }
        assertThat(terminalError.captured).isEqualTo(envelope)
        assertThat(terminalError.captured).doesNotContain("provider")
    }

    @Test
    fun `StreamChunk错误转GenerationFailure保留稳定码参数与诊断`() = runTest {
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
                            id = "s-stream-error",
                            agentId = "agent",
                            modelId = "local::model",
                            messages = listOf(Message("a-stream-error", MessageRole.ASSISTANT, "")),
                        ),
                    ),
                )
            }
        }
        val contextBuilder = mockk<ContextBuilder>()
        coEvery { contextBuilder.buildContext(any()) } returns ContextBuilderResult(
            searchContext = "",
            ragContext = "",
            citations = emptyList(),
            ragReferences = emptyList(),
            ragUsage = null,
            finalSystemPrompt = "system",
        )
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.Local
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
                emit(
                    StreamChunk.Error(
                        code = GenerationFailureCode.RATE_LIMIT,
                        retryable = true,
                        retryAfterSeconds = 37,
                        technical = "HTTP 429 provider raw",
                    ),
                )
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
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = contextBuilder,
            messageManager = mockk(relaxed = true),
            localProviderFactory = { provider },
            provider = provider,
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
            "s-stream-error", "a-stream-error", "u", "question", emptyList(),
            GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )

        runtime.prepare(request)
        assertThat(runtime.buildContext(request)).isNotNull()
        val chunk = runtime.stream(request, attempt = 0).toList().single() as GenerationChunk.Failure

        assertThat(chunk.failure.code).isEqualTo(GenerationFailureCode.RATE_LIMIT)
        assertThat(chunk.failure.retryAfterSeconds).isEqualTo(37)
        assertThat(chunk.failure.technical).isEqualTo("HTTP 429 provider raw")
    }

    @Test
    fun `StreamChunk错误转GenerationFailure保留同一cause实例`() = runTest {
        val cause = IllegalStateException("provider-private")
        val chunk = StreamChunk.Error(
            code = GenerationFailureCode.SERVER,
            technical = "diagnostic",
            cause = cause,
        )

        val mapper = DefaultChatGenerationRuntime::class.java.getDeclaredMethod(
            "toGenerationChunk",
            StreamChunk::class.java,
        ).apply { isAccessible = true }
        val failure = mapper.invoke(runtimeForPrivateMapping(), chunk) as GenerationChunk.Failure

        assertThat(failure.failure.cause).isSameInstanceAs(cause)
    }

    private fun runtimeForPrivateMapping() = DefaultChatGenerationRuntime(
        settings = mockk(relaxed = true),
        applicationScope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
        store = ChatStore(),
        agentRepository = mockk(),
        configResolver = mockk(),
        routeGate = mockk(),
        contextBuilder = mockk(),
        messageManager = mockk(relaxed = true),
        localProviderFactory = { mockk() },
        provider = mockk(relaxed = true),
        toolLedger = mockk(),
        toolExecutor = mockk(),
        postProcessor = mockk(),
        memoryManager = null,
        summaryManager = mockk(),
        sessionManager = mockk(),
        contentStrategy = mockk(),
        ui = mockk(relaxed = true),
    )

    @Test
    fun `RAG引用必须在网络建连前同步持久化且可从Repository重载`() = runTest {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } returns emptySet()
        every { settings.getString(any(), any()) } returns ""
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val assistant = Message("a-rag", MessageRole.ASSISTANT, "")
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "s-rag",
                            agentId = "agent",
                            modelId = "local::model",
                            messages = listOf(
                                Message("u-rag", MessageRole.USER, "question"),
                                assistant,
                            ),
                        ),
                    ),
                )
            }
        }
        val persistedMessages = mutableMapOf(assistant.id to assistant)
        val messageRepository = object : IMessageRepository {
            override suspend fun insert(message: Message, sessionId: String) {
                persistedMessages[message.id] = message
            }

            override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {
                val current = requireNotNull(persistedMessages[messageId])
                @Suppress("UNCHECKED_CAST")
                persistedMessages[messageId] = current.copy(
                    content = updates["content"] as? String ?: current.content,
                    ragReferences = if ("ragReferences" in updates) {
                        updates["ragReferences"] as? List<RagReference>
                    } else {
                        current.ragReferences
                    },
                )
            }

            override suspend fun delete(messageId: String) {
                persistedMessages.remove(messageId)
            }

            override suspend fun deleteBySessionId(sessionId: String) = Unit
            override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) = Unit
            override suspend fun getById(messageId: String): Message? = persistedMessages[messageId]?.copy()
            override suspend fun getBySession(sessionId: String): List<Message> = persistedMessages.values.map { it.copy() }
            override suspend fun updateVectorizationStatus(
                messageId: String,
                status: String,
                isArchived: Boolean?,
            ) = Unit
        }
        val sessionRepository = object : ISessionRepository {
            override suspend fun create(session: Session) = Unit
            override suspend fun updatePartial(id: String, updates: Map<String, Any?>) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun getById(id: String): Session? = null
            override suspend fun getAll(): List<Session> = emptyList()
        }
        val messageManager = MessageManager(store, messageRepository, sessionRepository, this)
        val reference = RagReference(
            id = "chunk-1",
            content = "持久化引用正文",
            source = "产品文档",
            score = 0.92f,
            documentId = "doc-1",
        )
        val contextBuilder = mockk<ContextBuilder>()
        coEvery { contextBuilder.buildContext(any()) } returns ContextBuilderResult(
            searchContext = "",
            ragContext = "持久化引用正文",
            citations = emptyList(),
            ragReferences = listOf(reference),
            ragUsage = null,
            finalSystemPrompt = "system with rag",
        )
        var reloadedAtNetworkEntry: Message? = null
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.Local
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
                reloadedAtNetworkEntry = messageRepository.getById(assistant.id)
                return flow {
                    emit(StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN))
                }
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
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = contextBuilder,
            messageManager = messageManager,
            localProviderFactory = { provider },
            provider = provider,
            toolLedger = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = SessionManager(store, sessionRepository),
            contentStrategy = mockk(relaxed = true),
            ui = mockk(relaxed = true),
        )
        val request = GenerationRequest(
            "s-rag", "a-rag", "u-rag", "question", emptyList(),
            GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )

        runtime.prepare(request)
        runtime.buildContext(request)
        runtime.stream(request, attempt = 0)

        assertThat(reloadedAtNetworkEntry).isNotNull()
        assertThat(reloadedAtNetworkEntry!!.ragReferences).isEqualTo(listOf(reference))
        assertThat(store.getSession("s-rag")?.messages?.last()?.ragReferences).containsExactly(reference)
    }

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
        coEvery { toolExecutor.executeTools(any(), any(), any(), any(), any()) } returns Unit
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
                match { it.isEmpty() },
                match { it.isEmpty() },
            )
        }
    }

    @Test
    fun `CONTINUE强刷assistant后多轮Prompt保留全部工具配对`() = runTest {
        val settings = mockk<SharedPreferences>()
        every { settings.getInt(any(), any()) } answers { secondArg() }
        every { settings.getStringSet(any(), any()) } returns setOf("search", "read_file")
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
                emit(StreamChunk.Completed(
                    if (prompts.size <= 2) {
                        com.promenar.nexara.domain.generation.CompletionReason.TOOL_CALLS
                    } else {
                        com.promenar.nexara.domain.generation.CompletionReason.END_TURN
                    },
                    if (prompts.size <= 2) listOf("call-${prompts.size}") else emptyList(),
                ))
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
        val executorPreparedSnapshots = mutableListOf<List<ProtocolTool>>()
        coEvery { toolExecutor.executeTools(any(), any(), any(), any(), any()) } coAnswers {
            val sessionId = firstArg<String>()
            val calls = thirdArg<List<com.promenar.nexara.data.model.ToolCall>>()
            executorPreparedSnapshots += arg<List<ProtocolTool>>(4)
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
        val advertisedTools = listOf(
            ProtocolTool(
                function = ProtocolToolFunction("search", "search", "{\"type\":\"object\"}"),
                risk = ToolRisk.SAFE_READ,
                runtimeToolId = "search",
                sourceId = "builtin",
            ),
            ProtocolTool(
                function = ProtocolToolFunction("read_file", "read", "{\"type\":\"object\"}"),
                risk = ToolRisk.SAFE_READ,
                runtimeToolId = "read_file",
                sourceId = "builtin",
            ),
        )
        val skillRegistry = mockk<SkillRegistry>()
        every { skillRegistry.getAllTools(any()) } returns advertisedTools
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
            contentStrategy = DefaultChatGenerationContentStrategy(settings, skillRegistry),
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
        assertThat(executorPreparedSnapshots).isNotEmpty()
        assertThat(executorPreparedSnapshots.all { it == advertisedTools }).isTrue()
    }
}
