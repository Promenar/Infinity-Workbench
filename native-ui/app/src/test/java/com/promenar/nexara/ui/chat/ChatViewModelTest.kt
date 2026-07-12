package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.model.*
import com.promenar.nexara.data.remote.protocol.*
import com.promenar.nexara.data.remote.ProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ProviderResolutionError
import com.promenar.nexara.data.remote.ResolvedProviderModel
import com.promenar.nexara.data.remote.UnifiedLlmClient
import com.promenar.nexara.data.remote.UnifiedProviderConfig
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import com.promenar.nexara.data.backup.BackupContent
import com.promenar.nexara.data.backup.BackupDataSource
import com.promenar.nexara.data.backup.BackupRuntime
import com.promenar.nexara.data.backup.BackupSnapshot
import com.promenar.nexara.data.backup.ValidatedBackup
import com.promenar.nexara.data.generation.ChatProviderRouteGate
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.repository.IAgentRepository
import com.promenar.nexara.domain.usecase.AgentConfigResolver
import com.promenar.nexara.ui.chat.manager.ContextBuilderResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
@Config(application = TestNexaraApplication::class)
class ChatViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: ChatViewModel
    private lateinit var generationScope: CoroutineScope

    private val kgPath = KgPath(
        queryKeywords = listOf("Nexara"),
        nodes = listOf(KgNode("n1", "Nexara", "project")),
        edges = emptyList(),
    )

    @Test
    fun mergeToolCallsByIdKeepsStableOrderAndLatestCompletePayload() {
        val merged = mergeToolCallsById(
            existing = listOf(
                ToolCall("same", "", ""),
                ToolCall("safe", "read_file", "{}"),
            ),
            incoming = listOf(
                ToolCall("same", "write_file", "{\"path\":\"a\"}"),
                ToolCall("safe", "read_file", "{}"),
            ),
        )

        assertThat(merged.map { it.id }).containsExactly("same", "safe").inOrder()
        assertThat(merged.first()).isEqualTo(
            ToolCall("same", "write_file", "{\"path\":\"a\"}"),
        )
    }

    @Test
    fun fallbackToolCallIdIsStableForTheSameLogicalCall() {
        val first = stableFallbackToolCallId("fallback", "write_file", "{\"path\":\"a\"}", 0)
        val rebuilt = stableFallbackToolCallId("fallback", "write_file", "{\"path\":\"a\"}", 0)
        val different = stableFallbackToolCallId("fallback", "write_file", "{\"path\":\"b\"}", 0)

        assertThat(rebuilt).isEqualTo(first)
        assertThat(different).isNotEqualTo(first)
    }

    @Test
    fun contextResultWithOnlyKgPathsKeepsRagStateAndBuildsKgUpdate() {
        val result = ContextBuilderResult(
            searchContext = "",
            finalSystemPrompt = "system",
            ragContext = "",
            citations = emptyList(),
            ragReferences = emptyList(),
            ragUsage = null,
            kgPaths = listOf(kgPath),
        )

        assertThat(result.hasPersistableRagContext()).isTrue()
        val options = result.toMessageRagUpdateOptions()
        assertThat(options).isNotNull()
        assertThat(options!!.kgPaths).containsExactly(kgPath)
        assertThat(options.ragReferences).isNull()
        assertThat(options.citations).isNull()
        assertThat(options.ragMetadata).isNull()
    }

    @Test
    fun contextResultWithReferencesAndKgPathsBuildsOneCompleteUpdate() {
        val reference = RagReference(id = "r1", content = "content", source = "doc", score = 0.9f)
        val result = ContextBuilderResult(
            searchContext = "",
            finalSystemPrompt = "system",
            ragContext = "context",
            citations = emptyList(),
            ragReferences = listOf(reference),
            ragUsage = RagUsage(ragSystem = 12),
            kgPaths = listOf(kgPath),
        )

        val options = result.toMessageRagUpdateOptions()
        assertThat(options).isNotNull()
        assertThat(options!!.ragReferences).containsExactly(reference)
        assertThat(options.kgPaths).containsExactly(kgPath)
        assertThat(options.ragMetadata?.chunkCount).isEqualTo(1)
        assertThat(options.ragMetadata?.totalTokens).isEqualTo(12)
    }

    @Test
    fun emptyKgPathsDoNotCreateMessageUpdateOrFakeRagMetadata() {
        val result = ContextBuilderResult(
            searchContext = "",
            finalSystemPrompt = "system",
            ragContext = "",
            citations = emptyList(),
            ragReferences = emptyList(),
            ragUsage = null,
            kgPaths = emptyList(),
        )

        assertThat(result.hasPersistableRagContext()).isFalse()
        assertThat(result.toMessageRagUpdateOptions()).isNull()
    }

    @Test
    fun applicationDoesNotKeepCredentialBearingProviderStateFlow() {
        assertThat(NexaraApplication::class.java.declaredFields.map { it.name })
            .doesNotContain("_llmProvider")
    }

    private val savedSessions = mutableListOf<Session>()
    private val savedMessages = mutableListOf<Pair<Message, String>>()
    private val deletedMessages = mutableListOf<String>()
    private var failScopedDelete = false
    private var afterInsert: suspend (Message, String) -> Unit = { _, _ -> }

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {
            savedSessions.add(session)
        }

        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {
            val index = savedSessions.indexOfFirst { it.id == id }
            if (index != -1) {
                var session = savedSessions[index]
                if (updates.containsKey("title")) {
                    session = session.copy(title = updates["title"] as String)
                }
                if (updates.containsKey("stats")) {
                    session = session.copy(stats = updates["stats"] as SessionStats?)
                }
                if (updates.containsKey("draft")) {
                    session = session.copy(draft = updates["draft"] as String?)
                }
                savedSessions[index] = session
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
            afterInsert(message, sessionId)
        }

        override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {
            val index = savedMessages.indexOfFirst { it.first.id == messageId }
            if (index != -1) {
                val (msg, sid) = savedMessages[index]
                var updatedMsg = msg
                if (updates.containsKey("content")) {
                    updatedMsg = updatedMsg.copy(content = updates["content"] as String)
                }
                if (updates.containsKey("reasoning")) {
                    updatedMsg = updatedMsg.copy(reasoning = updates["reasoning"] as String?)
                }
                if (updates.containsKey("tokens")) {
                    updatedMsg = updatedMsg.copy(tokens = updates["tokens"] as TokenUsage)
                }
                if (updates.containsKey("citations")) {
                    @Suppress("UNCHECKED_CAST")
                    updatedMsg = updatedMsg.copy(citations = updates["citations"] as List<Citation>)
                }
                if (updates.containsKey("toolCalls")) {
                    @Suppress("UNCHECKED_CAST")
                    updatedMsg = updatedMsg.copy(toolCalls = updates["toolCalls"] as List<ToolCall>)
                }
                savedMessages[index] = updatedMsg to sid

                // 同步更新 session 中的 messages 缓存
                savedSessions.find { it.id == sid }?.let { session ->
                    val updatedMsgs = session.messages.map { 
                        if (it.id == messageId) updatedMsg else it 
                    }
                    val updatedSession = session.copy(messages = updatedMsgs)
                    savedSessions.removeIf { it.id == sid }
                    savedSessions.add(updatedSession)
                }
            }
        }
        override suspend fun delete(messageId: String) {
            deletedMessages.add(messageId)
            savedMessages.removeIf { it.first.id == messageId }
            savedSessions.forEachIndexed { index, session ->
                savedSessions[index] = session.copy(messages = session.messages.filter { it.id != messageId })
            }
        }
        override suspend fun deleteInSession(sessionId: String, messageId: String): Boolean {
            if (failScopedDelete) throw IllegalStateException("delete blocked")
            if (savedMessages.none { it.second == sessionId && it.first.id == messageId }) return false
            delete(messageId)
            return true
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
    private var protocolRequestCount = 0
    private var holdStreamOpen = false
    private var providerCancelled = false
    private var forcedProviderFailure: ProviderResolution.Failure? = null

    private val fakeProtocol = object : LlmProtocol {
        override val protocolType = ProtocolType.OpenAI_ChatCompletions
        override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
            protocolRequestCount += 1
            return flow {
                for (chunk in fakeStreamChunks) {
                    emit(chunk)
                }
                if (holdStreamOpen) kotlinx.coroutines.awaitCancellation()
            }
        }

        override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
            return PromptResponse(content = "test response")
        }

        override fun cancel() { providerCancelled = true }
    }

    private val fakeLlmProvider = LlmProvider(fakeProtocol)
    private val fakeProviderRouter = object : ProviderRequestRouter {
        override fun resolve(modelId: String): ProviderResolution = forcedProviderFailure ?: ProviderResolution.Success(
            ResolvedProviderModel(
                modelId = modelId,
                remoteModelId = modelId.substringAfter("::", modelId),
                providerId = "test-local",
                providerName = "测试本地 Provider",
                config = UnifiedProviderConfig(
                    protocolType = ProtocolType.Local,
                    baseUrl = "",
                    apiKey = "",
                    defaultModel = modelId.substringAfter("::", modelId),
                ),
            )
        )

        override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient =
            error("本地测试路由不应创建云客户端")
    }

    private val fakeTaskRepository = object : com.promenar.nexara.domain.repository.ITaskRepository {
        override fun observeActiveTree(sessionId: String): Flow<List<TaskStep>> = kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun initializePlan(sessionId: String, goal: String, tree: List<TaskStep>): TaskState = TaskState(id = "", title = goal, status = "idle", steps = emptyList())
        override suspend fun updatePlan(sessionId: String, operations: List<com.promenar.nexara.domain.repository.PlanPatchOp>): TaskState = TaskState(id = "", title = "", status = "idle", steps = emptyList())
        override suspend fun getPlan(sessionId: String): TaskState? = null
        override suspend fun dropPlan(sessionId: String, reason: String) {}
        override fun deriveParentStatus(children: List<TaskStep>): String = "todo"
        override fun countLeafProgress(steps: List<TaskStep>): Pair<Int, Int> = 0 to 0
    }

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
        val app = ApplicationProvider.getApplicationContext<NexaraApplication>()
        
        try {
            val delegateField = NexaraApplication::class.java.getDeclaredField("taskRepository\$delegate")
            delegateField.isAccessible = true
            val customLazy = lazy { fakeTaskRepository }
            delegateField.set(app, customLazy)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        app.chatStore.clear()
        
        savedSessions.clear()
        savedMessages.clear()
        deletedMessages.clear()
        fakeStreamChunks = emptyList()
        failScopedDelete = false
        afterInsert = { _, _ -> }
        protocolRequestCount = 0
        holdStreamOpen = false
        providerCancelled = false
        forcedProviderFailure = null

        stubAgentRepo.seed(Agent(
            id = "a1",
            name = "Test Agent",
            systemPrompt = "test prompt",
            modelId = "gpt-4o"
        ))

        val configResolver = AgentConfigResolver(
            app.getSharedPreferences("nexara_settings", 0)
        )
        generationScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val presentationStore = com.promenar.nexara.data.generation.GenerationPresentationStore()
        val generationSessionManager = com.promenar.nexara.ui.chat.manager.SessionManager(app.chatStore, stubSessionRepo)
        val generationMessageManager = com.promenar.nexara.ui.chat.manager.MessageManager(
            app.chatStore,
            stubMessageRepo,
            stubSessionRepo,
            generationScope,
        )
        val generationToolLedger = io.mockk.mockk<com.promenar.nexara.data.repository.ToolExecutionLedger>(relaxed = true)
        val generationToolExecutor = com.promenar.nexara.ui.chat.manager.ToolExecutor(
            app.chatStore,
            generationMessageManager,
            null,
            fakeTaskRepository,
            generationToolLedger,
        )
        val runnerFactory = com.promenar.nexara.data.generation.DefaultChatGenerationRunnerFactory(
            settings = app.getSharedPreferences("nexara_settings", 0),
            applicationScope = generationScope,
            store = app.chatStore,
            agentRepository = stubAgentRepo,
            configResolver = configResolver,
            routeGate = ChatProviderRouteGate(fakeProviderRouter, testDispatcher),
            contextBuilder = com.promenar.nexara.ui.chat.manager.ContextBuilder(taskRepository = fakeTaskRepository),
            messageManager = generationMessageManager,
            localProviderFactory = { fakeLlmProvider },
            provider = fakeLlmProvider,
            toolLedger = generationToolLedger,
            toolExecutor = generationToolExecutor,
            postProcessor = com.promenar.nexara.ui.chat.manager.PostProcessor(
                app.chatStore,
                generationSessionManager,
                generationMessageManager,
            ),
            memoryManager = null,
            summaryManager = com.promenar.nexara.ui.chat.manager.SummaryManager(fakeLlmProvider),
            sessionManager = generationSessionManager,
            skillRegistry = null,
            presentationStore = presentationStore,
        )
        val generationCoordinator = com.promenar.nexara.data.generation.DefaultGenerationCoordinator(
            applicationScope = generationScope,
            runnerFactory = runnerFactory,
            presentationStore = presentationStore,
        )

        viewModel = ChatViewModel(
            application = app,
            sessionRepository = stubSessionRepo,
            messageRepository = stubMessageRepo,
            agentRepository = stubAgentRepo,
            llmProvider = fakeLlmProvider,
            providerRequestRouter = fakeProviderRouter,
            providerResolutionDispatcher = testDispatcher,
            localLlmProviderFactory = { fakeLlmProvider },
            configResolver = configResolver,
            generationCoordinatorOverride = generationCoordinator,
            generationPresentationStoreOverride = presentationStore,
        )
    }

    @After
    fun tearDown() {
        generationScope.cancel()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun seedSession(id: String = "s1") {
        val session = Session(
            id = id,
            agentId = "a1",
            title = "Test",
            modelId = "gpt-4o",
            workspaceRootUuid = "test-workspace-root",
            workspacePath = "/test/workspace",
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
        
        val app = ApplicationProvider.getApplicationContext<com.promenar.nexara.NexaraApplication>()
        println("=== DIAGNOSTIC LOG ===")
        println("chatStore sessions size: ${app.chatStore.get().sessions.size}")
        if (app.chatStore.get().sessions.isNotEmpty()) {
            println("chatStore session id: ${app.chatStore.get().sessions[0].id}")
            println("chatStore session messages size: ${app.chatStore.get().sessions[0].messages.size}")
        }
        println("currentSessionId: ${viewModel.uiState.value.session?.id}")
        println("uiState messages size: ${viewModel.uiState.value.messages.size}")
        println("=======================")

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
    fun `完成终态被当前VM消费后回到IDLE`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession()
        advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("done"), StreamChunk.Done)

        viewModel.sendMessage("test")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isGenerating).isFalse()
        assertThat(viewModel.uiState.value.status).isEqualTo(GenerationStatus.IDLE)
    }

    @Test
    fun `Coordinator终态先到时combine仍保留路由Rejected设置入口直至UI消费`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession()
        advanceUntilIdle()
        forcedProviderFailure = ProviderResolution.Failure(
            reason = ProviderResolutionError.API_KEY_MISSING,
            modelId = "gpt-4o",
            providerId = "provider-missing-key",
        )

        viewModel.sendMessage("trigger route failure")
        advanceUntilIdle()

        assertThat(viewModel.providerResolutionFailure.value?.providerId)
            .isEqualTo("provider-missing-key")
        assertThat(viewModel.uiState.value.error).contains("Provider 路由失败")
        assertThat(viewModel.uiState.value.status).isEqualTo(GenerationStatus.ERROR)
        assertThat(viewModel.uiState.value.isGenerating).isFalse()

        seedSession("clean-session")
        advanceUntilIdle()
        assertThat(viewModel.providerResolutionFailure.value).isNull()
        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.ragPhases.value).isEmpty()
        assertThat(viewModel.uiState.value.status).isEqualTo(GenerationStatus.IDLE)
    }

    @Test
    fun stopGeneration_resetsIsGenerating() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        

        assertThat(viewModel.uiState.value.isGenerating).isFalse()
        
    }

    @Test
    fun `A生成时B发送返回Busy且不留幽灵消息并且B停止不取消A`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession("A")
        advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("partial-A"))
        holdStreamOpen = true
        viewModel.sendMessage("from A")
        advanceUntilIdle()
        assertThat(protocolRequestCount).isEqualTo(1)

        seedSession("B")
        advanceUntilIdle()
        viewModel.updateInputText("from B")
        viewModel.sendMessage("from B")
        advanceUntilIdle()

        val app = ApplicationProvider.getApplicationContext<NexaraApplication>()
        assertThat(app.chatStore.getSession("B")!!.messages).isEmpty()
        assertThat(viewModel.inputText.value).isEqualTo("from B")
        assertThat(viewModel.uiState.value.isGenerating).isFalse()
        assertThat(viewModel.uiState.value.status).isEqualTo(GenerationStatus.ERROR)
        assertThat(protocolRequestCount).isEqualTo(1)
        assertThat(providerCancelled).isFalse()

        viewModel.stopGeneration()
        advanceUntilIdle()
        assertThat(providerCancelled).isFalse()

        viewModel.loadSession("A")
        advanceUntilIdle()
        viewModel.stopGeneration()
        advanceUntilIdle()
        assertThat(providerCancelled).isTrue()
    }

    @Test
    fun `同会话并发双击只保留一组消息`() = runTest {
        seedSession("A")
        advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("partial"))
        holdStreamOpen = true

        viewModel.sendMessage("first")
        advanceUntilIdle()
        viewModel.updateInputText("second")
        viewModel.sendMessage("second")
        advanceUntilIdle()

        val session = ApplicationProvider.getApplicationContext<NexaraApplication>()
            .chatStore.getSession("A")!!
        assertThat(session.messages.map { it.content }).containsExactly("first", "partial").inOrder()
        assertThat(protocolRequestCount).isEqualTo(1)
        assertThat(viewModel.inputText.value).isEqualTo("second")
    }

    @Test
    fun `assistant插入已提交但返回前取消时第二click不留下首轮幽灵pair`() = runTest {
        seedSession("A")
        advanceUntilIdle()
        val committedBeforeReturn = kotlinx.coroutines.CompletableDeferred<Unit>()
        val secondPreparationEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseSecondPreparation = kotlinx.coroutines.CompletableDeferred<Unit>()
        var suspendOnce = true
        afterInsert = { message, _ ->
            if (message.role == MessageRole.ASSISTANT && suspendOnce) {
                suspendOnce = false
                committedBeforeReturn.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            } else if (message.role == MessageRole.USER && message.content == "second") {
                secondPreparationEntered.complete(Unit)
                releaseSecondPreparation.await()
            }
        }

        viewModel.updateInputText("first")
        viewModel.sendMessage("first")
        committedBeforeReturn.await()
        viewModel.updateInputText("second")
        holdStreamOpen = true
        fakeStreamChunks = listOf(StreamChunk.TextDelta("second-response"))
        viewModel.sendMessage("second")
        secondPreparationEntered.await()
        assertThat(viewModel.inputText.value).isEqualTo("second")
        assertThat(savedSessions.single { it.id == "A" }.draft).isEqualTo("second")
        releaseSecondPreparation.complete(Unit)
        advanceUntilIdle()

        val storeMessages = ApplicationProvider.getApplicationContext<NexaraApplication>()
            .chatStore.getSession("A")!!.messages
        val repositoryMessages = savedMessages.filter { it.second == "A" }.map { it.first }
        assertThat(storeMessages.map { it.content })
            .containsExactly("second", "second-response").inOrder()
        assertThat(repositoryMessages.map { it.content })
            .containsExactly("second", "second-response").inOrder()
        assertThat(storeMessages.none { it.content == "first" }).isTrue()
        assertThat(repositoryMessages.none { it.content == "first" }).isTrue()
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
    fun retryDeletionFailureDoesNotStartAnotherProtocolRequest() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("first response"), StreamChunk.Done)
        viewModel.sendMessage("hello"); advanceUntilIdle()
        val requestsBeforeRetry = protocolRequestCount
        failScopedDelete = true

        viewModel.retryLastMessage(); advanceUntilIdle()

        assertThat(protocolRequestCount).isEqualTo(requestsBeforeRetry)
        assertThat(viewModel.uiState.value.error).contains("取消重试")
        assertThat(viewModel.uiState.value.messages.any { it.content == "first response" }).isTrue()
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
    fun `onCleared只移除观察者不取消应用级生成`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        holdStreamOpen = true
        fakeStreamChunks = listOf(StreamChunk.TextDelta("partial"))
        viewModel.sendMessage("hello")
        kotlinx.coroutines.yield()
        assertThat(protocolRequestCount).isGreaterThan(0)
        providerCancelled = false

        ChatViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(viewModel)

        assertThat(providerCancelled).isFalse()
        assertThat(viewModel.uiState.value.isGenerating).isTrue()
        viewModel.stopGeneration()
    }

    @Test
    fun loadSession_fetchesAgentNameFromRepository() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()

        assertThat(stubAgentRepo.lastGetByIdId).isEqualTo("a1")
    }
}

class TestNexaraApplication : NexaraApplication() {
    override val secretStore: SecretStore = MemorySecretStore()
    override fun createBackupRuntime(): BackupRuntime = BackupRuntime(object : BackupDataSource {
        override suspend fun snapshot(content: Set<BackupContent>): BackupSnapshot = error("unused")
        override suspend fun restore(validated: ValidatedBackup) = error("unused")
        override suspend fun recoverInterruptedRestore() = Unit
    })
}

private class MemorySecretStore : SecretStore {
    private val values = mutableMapOf<SecretId, ByteArray>()
    override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
    override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
    override fun contains(id: SecretId): Boolean = id in values
    override fun remove(id: SecretId) { values.remove(id) }
}
