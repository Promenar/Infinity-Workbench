package com.promenar.nexara.ui.chat

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
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
import com.promenar.nexara.data.document.DocumentReadResult
import com.promenar.nexara.data.document.FullContextDocumentReader
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.repository.IAgentRepository
import com.promenar.nexara.domain.usecase.AgentConfigResolver
import com.promenar.nexara.ui.chat.manager.ContextBuilderResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

private const val BACKGROUND_UNAVAILABLE_MESSAGE = "background generation unavailable"
private const val NOTIFICATIONS_UNAVAILABLE_MESSAGE = "notifications unavailable"
private const val BACKGROUND_SERVICE_STOPPED_MESSAGE = "background service stopped"

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestNexaraApplication::class)
class ChatViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: ChatViewModel
    private lateinit var generationScope: CoroutineScope
    private val foregroundTrackedTasks = mutableListOf<
        com.promenar.nexara.domain.generation.GenerationTaskSnapshot
    >()
    private var foregroundStartResult:
        com.promenar.nexara.background.generation.ForegroundStartResult =
        com.promenar.nexara.background.generation.ForegroundStartResult.StartedWithNotifications
    private val foregroundFailures = kotlinx.coroutines.flow.MutableSharedFlow<
        com.promenar.nexara.background.generation.ForegroundServiceFailure
    >(extraBufferCapacity = 4)
    private var notificationPermissionState:
        com.promenar.nexara.background.generation.NotificationPermissionPromptState? = null

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
    private var failSessionUpdate: Throwable? = null
    private var afterInsert: suspend (Message, String) -> Unit = { _, _ -> }

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {
            savedSessions.add(session)
        }

        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {
            failSessionUpdate?.let { throw it }
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
                if (updates.containsKey("modelId")) {
                    session = session.copy(modelId = updates["modelId"] as String?)
                }
                if (updates.containsKey("customPrompt")) {
                    session = session.copy(customPrompt = updates["customPrompt"] as String?)
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
    private var lastPromptRequest: PromptRequest? = null
    private var holdStreamOpen = false
    private var providerCancelled = false
    private var forcedProviderFailure: ProviderResolution.Failure? = null

    @Test
    fun createNewSessionInheritsAgentExecutionModeAndOrderedToolSelections() = runTest {
        stubAgentRepo.create(
            Agent(
                id = "approval-agent",
                name = "Approval Agent",
                executionMode = com.promenar.nexara.domain.model.ExecutionMode.MANUAL,
                skills = listOf("read_file", "calculator"),
                mcpServerIds = listOf("server-a", "server-b"),
            ),
        )

        viewModel.createNewSession("approval-agent")
        advanceUntilIdle()

        val created = savedSessions.single { it.agentId == "approval-agent" }
        assertThat(created.executionMode).isEqualTo("manual")
        assertThat(created.activeSkillIds).containsExactly("read_file", "calculator").inOrder()
        assertThat(created.activeMcpServerIds).containsExactly("server-a", "server-b").inOrder()
    }

    @Test
    fun createNewSessionWithoutAgentFallsBackToSemiAndEmptyToolSelections() = runTest {
        viewModel.createNewSession("missing-approval-agent")
        advanceUntilIdle()

        val created = savedSessions.single { it.agentId == "missing-approval-agent" }
        assertThat(created.executionMode).isEqualTo("semi")
        assertThat(created.activeSkillIds).isEmpty()
        assertThat(created.activeMcpServerIds).isEmpty()
    }

    private val fakeProtocol = object : LlmProtocol {
        override val protocolType = ProtocolType.OpenAI_ChatCompletions
        override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
            protocolRequestCount += 1
            lastPromptRequest = request
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
        failSessionUpdate = null
        afterInsert = { _, _ -> }
        protocolRequestCount = 0
        lastPromptRequest = null
        holdStreamOpen = false
        providerCancelled = false
        foregroundTrackedTasks.clear()
        foregroundStartResult =
            com.promenar.nexara.background.generation.ForegroundStartResult.StartedWithNotifications
        forcedProviderFailure = null
        notificationPermissionState = null

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
        val generationToolResolver = com.promenar.nexara.data.generation.DefaultSessionToolResolver(
            app.getSharedPreferences("nexara_settings", 0),
            null,
        )
        val generationToolExecutor = com.promenar.nexara.ui.chat.manager.ToolExecutor(
            app.chatStore,
            generationMessageManager,
            null,
            generationToolResolver,
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
            toolResolver = generationToolResolver,
            presentationStore = presentationStore,
        )
        val generationCoordinator = com.promenar.nexara.data.generation.DefaultGenerationCoordinator(
            applicationScope = generationScope,
            runnerFactory = runnerFactory,
            presentationStore = presentationStore,
        )

        val documentReader = io.mockk.mockk<FullContextDocumentReader>()
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
            sessionToolResolver = generationToolResolver,
            fullContextDocumentReader = documentReader,
            generationCoordinatorOverride = generationCoordinator,
            generationPresentationStoreOverride = presentationStore,
            generationForegroundControllerOverride =
                object : com.promenar.nexara.background.generation.GenerationForegroundController {
                    override val failures = foregroundFailures
                    override fun track(
                        snapshot: com.promenar.nexara.domain.generation.GenerationTaskSnapshot,
                    ): com.promenar.nexara.background.generation.ForegroundStartResult {
                        foregroundTrackedTasks += snapshot
                        return foregroundStartResult
                    }
                },
            notificationPermissionGateway = { notificationPermissionState },
            stringResourceResolverOverride = { resourceId ->
                when (resourceId) {
                    com.promenar.nexara.R.string.generation_background_unavailable ->
                        BACKGROUND_UNAVAILABLE_MESSAGE
                    com.promenar.nexara.R.string.generation_notifications_unavailable ->
                        NOTIFICATIONS_UNAVAILABLE_MESSAGE
                    com.promenar.nexara.R.string.generation_background_service_stopped ->
                        BACKGROUND_SERVICE_STOPPED_MESSAGE
                    com.promenar.nexara.R.string.chat_document_error_unsupported -> "unsupported document"
                    com.promenar.nexara.R.string.chat_document_error_empty -> "empty document"
                    com.promenar.nexara.R.string.chat_document_error_too_large -> "document too large"
                    com.promenar.nexara.R.string.chat_document_error_binary -> "binary document"
                    com.promenar.nexara.R.string.chat_document_error_encoding -> "invalid encoding"
                    com.promenar.nexara.R.string.chat_document_error_read -> "document read failed"
                    com.promenar.nexara.R.string.chat_document_import_in_progress ->
                        "document import in progress"
                    com.promenar.nexara.R.string.chat_document_error_unknown_capacity -> "unknown capacity"
                    com.promenar.nexara.R.string.chat_document_error_invalid_estimate -> "invalid estimate"
                    com.promenar.nexara.R.string.chat_document_error_over_capacity ->
                        "requires %1\$d tokens, %2\$d available"
                    else -> error("unexpected string resource: $resourceId")
                }
            },
            sessionWorkspaceRootOverride = { "test-workspace-root" to "/test/workspace" },
        )
        this.documentReader = documentReader
    }

    private lateinit var documentReader: FullContextDocumentReader

    @After
    fun tearDown() {
        val viewModelJob = viewModel.viewModelScope.coroutineContext.job
        runBlocking {
            generationScope.coroutineContext.job.cancelAndJoin()
        }
        ViewModelStore().also { it.put("chat", viewModel) }.clear()
        runBlocking {
            viewModelJob.join()
        }
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
    fun updateModelId通过会话管理器同步Store并持久化同一模型() = runTest {
        seedSession()
        advanceUntilIdle()

        viewModel.updateModelId("provider-b::model-b")
        advanceUntilIdle()

        assertThat((ApplicationProvider.getApplicationContext() as TestNexaraApplication)
            .chatStore.getSession("s1")?.modelId).isEqualTo("provider-b::model-b")
        assertThat(savedSessions.single { it.id == "s1" }.modelId)
            .isEqualTo("provider-b::model-b")
    }

    @Test
    fun saveCustomPrompt等待仓库成功后才更新Store() = runTest {
        seedSession()
        advanceUntilIdle()

        val result = viewModel.saveCustomPrompt("persisted")

        assertThat(result.isSuccess).isTrue()
        assertThat(savedSessions.single { it.id == "s1" }.customPrompt).isEqualTo("persisted")
        assertThat((ApplicationProvider.getApplicationContext() as TestNexaraApplication)
            .chatStore.getSession("s1")?.customPrompt).isEqualTo("persisted")
    }

    @Test
    fun saveCustomPrompt仓库失败时不更新Store() = runTest {
        seedSession()
        advanceUntilIdle()
        failSessionUpdate = IllegalStateException("room failed")

        val result = viewModel.saveCustomPrompt("not-persisted")

        assertThat(result.isFailure).isTrue()
        assertThat((ApplicationProvider.getApplicationContext() as TestNexaraApplication)
            .chatStore.getSession("s1")?.customPrompt).isNull()
    }

    @Test
    fun saveCustomPrompt取消必须传播() = runTest {
        seedSession()
        advanceUntilIdle()
        failSessionUpdate = CancellationException("cancelled")

        var thrown: CancellationException? = null
        try {
            viewModel.saveCustomPrompt("cancelled")
        } catch (cancelled: CancellationException) {
            thrown = cancelled
        }
        assertThat(thrown?.message).isEqualTo("cancelled")
    }

    @Test
    fun sendMessage_createsUserAndAssistantMessages() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN))

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
        assertThat(foregroundTrackedTasks.map { it.sessionId }).containsExactly("s1")
        
    }

    @Test
    fun sendMessage_persistsMessagesToRepository() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN))

        viewModel.sendMessage("hello"); advanceUntilIdle()
        

        assertThat(savedMessages).hasSize(2)
        assertThat(savedMessages[0].first.role).isEqualTo(MessageRole.USER)
        assertThat(savedMessages[0].second).isEqualTo("s1")
        
    }

    @Test
    fun `完整文档随用户消息持久化并进入最终协议正文`() = runTest {
        val document = MessageDocumentAttachment(
            id = "doc-1",
            name = "history.md",
            mimeType = "text/markdown",
            content = "# 历史\n完整正文",
            sizeBytes = 20,
            sha256 = "hash",
            estimatedTokens = 5,
        )
        io.mockk.coEvery { documentReader.read(any()) } returns DocumentReadResult.Success(document)
        ApplicationProvider.getApplicationContext<TestNexaraApplication>()
            .getSharedPreferences("nexara_settings", 0)
            .edit()
            .putInt("model_info_gpt-4o_context", 128_000)
            .commit()
        seedSession()
        advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN))

        viewModel.importFullContextDocument(android.net.Uri.parse("content://test/history"))
        advanceUntilIdle()
        viewModel.sendMessage("继续分析")
        advanceUntilIdle()

        assertThat(savedMessages.first().first.userDocuments).containsExactly(document)
        val userProtocol = lastPromptRequest!!.messages.last { it.role == "user" }
        assertThat(userProtocol.content).contains("继续分析")
        assertThat(userProtocol.content).contains(document.content)
        assertThat(viewModel.draftDocuments.value).isEmpty()
        assertThat(viewModel.draftConsumptionEpoch.value).isEqualTo(1L)
    }

    @Test
    fun `多文档按选择顺序读取且全部结束前保持导入状态`() = runTest {
        val firstUri = android.net.Uri.parse("content://test/first")
        val secondUri = android.net.Uri.parse("content://test/second")
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val first = MessageDocumentAttachment(
            "first", "first.txt", "text/plain", "one", 3L, "hash-1", 1,
        )
        val second = MessageDocumentAttachment(
            "second", "second.md", "text/markdown", "two", 3L, "hash-2", 1,
        )
        io.mockk.coEvery { documentReader.read(firstUri) } coAnswers {
            firstGate.await()
            DocumentReadResult.Success(first)
        }
        io.mockk.coEvery { documentReader.read(secondUri) } coAnswers {
            secondGate.await()
            DocumentReadResult.Success(second)
        }

        viewModel.importFullContextDocuments(listOf(firstUri, secondUri))
        runCurrent()
        assertThat(viewModel.isImportingDocument.value).isTrue()

        firstGate.complete(Unit)
        runCurrent()
        assertThat(viewModel.isImportingDocument.value).isTrue()

        secondGate.complete(Unit)
        advanceUntilIdle()
        assertThat(viewModel.isImportingDocument.value).isFalse()
        assertThat(viewModel.draftDocuments.value).containsExactly(first, second).inOrder()
    }

    @Test
    fun `文档读取期间发送被阻止且不持久化消息`() = runTest {
        val uri = android.net.Uri.parse("content://test/pending")
        val gate = CompletableDeferred<Unit>()
        io.mockk.coEvery { documentReader.read(uri) } coAnswers {
            gate.await()
            DocumentReadResult.Rejected(
                com.promenar.nexara.data.document.DocumentReadRejectReason.ReadFailed,
            )
        }
        seedSession()
        advanceUntilIdle()

        viewModel.importFullContextDocuments(listOf(uri))
        runCurrent()
        viewModel.sendMessage("不能抢跑")
        runCurrent()

        assertThat(savedMessages).isEmpty()
        assertThat(protocolRequestCount).isEqualTo(0)
        assertThat(viewModel.uiState.value.error).isEqualTo("document import in progress")
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `完整文档超预算时不持久化且不调用Provider`() = runTest {
        val document = MessageDocumentAttachment(
            id = "doc-large",
            name = "large.txt",
            mimeType = "text/plain",
            content = "中".repeat(200_000),
            sizeBytes = 600_000,
            sha256 = "large-hash",
            estimatedTokens = 200_000,
        )
        io.mockk.coEvery { documentReader.read(any()) } returns DocumentReadResult.Success(document)
        ApplicationProvider.getApplicationContext<TestNexaraApplication>()
            .getSharedPreferences("nexara_settings", 0)
            .edit()
            .putInt("model_info_gpt-4o_context", 128_000)
            .commit()
        seedSession()
        advanceUntilIdle()

        viewModel.importFullContextDocument(android.net.Uri.parse("content://test/large"))
        advanceUntilIdle()
        viewModel.sendMessage("请分析")
        advanceUntilIdle()

        assertThat(savedMessages).isEmpty()
        assertThat(protocolRequestCount).isEqualTo(0)
        assertThat(viewModel.uiState.value.generationNotice?.code)
            .isEqualTo("generation.failure.context_limit")
        assertThat(viewModel.draftDocuments.value).containsExactly(document)
        assertThat(viewModel.draftConsumptionEpoch.value).isEqualTo(0L)

        viewModel.clearError()
        assertThat(viewModel.uiState.value.status).isEqualTo(GenerationStatus.IDLE)
    }

    @Test
    fun sendMessage_streamingResponseUpdatesStreamingContent() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("Hello"),
            StreamChunk.TextDelta(" world"),
            StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN)
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
            StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN)
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
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("done"),
            StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN),
        )

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
        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.generationNotice?.code)
            .isEqualTo("generation.failure.auth")
        assertThat(viewModel.uiState.value.generationNotice?.formatArgs).isEmpty()
        assertThat(viewModel.uiState.value.generationNotice?.formatArgs?.joinToString())
            .doesNotContain("provider-missing-key")
        assertThat(viewModel.uiState.value.status).isEqualTo(GenerationStatus.ERROR)
        assertThat(viewModel.uiState.value.isGenerating).isFalse()

        seedSession("clean-session")
        advanceUntilIdle()
        assertThat(viewModel.providerResolutionFailure.value).isNull()
        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.generationNotice).isNull()
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
    fun `FGS启动不允许时提示回复仍在生成而不是误报取消`() = runTest {
        seedSession("fgs-not-allowed")
        advanceUntilIdle()
        holdStreamOpen = true
        foregroundStartResult = com.promenar.nexara.background.generation.ForegroundStartResult.NotAllowed(
            android.app.ForegroundServiceStartNotAllowedException("blocked"),
        )

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.backgroundWarning?.message)
            .isEqualTo(BACKGROUND_UNAVAILABLE_MESSAGE)
        assertThat(viewModel.uiState.value.isGenerating).isTrue()
    }

    @Test
    fun `FGS权限异常时降级提示且应用内Stop仍可取消当前task`() = runTest {
        seedSession("fgs-permission")
        advanceUntilIdle()
        holdStreamOpen = true
        foregroundStartResult = com.promenar.nexara.background.generation.ForegroundStartResult.PermissionDenied(
            SecurityException("fgs permission"),
        )

        viewModel.sendMessage("hello")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.backgroundWarning?.message)
            .isEqualTo(BACKGROUND_UNAVAILABLE_MESSAGE)

        viewModel.stopGeneration()
        advanceUntilIdle()
        assertThat(providerCancelled).isTrue()
    }

    @Test
    fun `通知未授权时生成继续并按taskId发出一次权限请求且授权后清warning`() = runTest {
        seedSession("notification-permission")
        advanceUntilIdle()
        holdStreamOpen = true
        foregroundStartResult =
            com.promenar.nexara.background.generation.ForegroundStartResult.StartedWithoutNotifications
        assertThat(viewModel.notificationPermissionRequests.value).isNull()
        viewModel.sendMessage("hello")
        advanceUntilIdle()

        val permissionRequest = requireNotNull(viewModel.notificationPermissionRequests.value)
        val warning = viewModel.uiState.value.backgroundWarning!!
        assertThat(permissionRequest.taskId).isEqualTo(warning.taskId)
        assertThat(warning.kind).isEqualTo(BackgroundWarningKind.NOTIFICATIONS_UNAVAILABLE)
        assertThat(viewModel.uiState.value.isGenerating).isTrue()

        viewModel.onNotificationPermissionResult(permissionRequest.taskId, granted = true)
        assertThat(viewModel.uiState.value.backgroundWarning).isNull()
        assertThat(viewModel.notificationPermissionRequests.value).isNull()
    }

    @Test
    fun `Android13首次发送先等待权限结果且不落消息不启动生成`() = runTest {
        notificationPermissionState =
            com.promenar.nexara.background.generation.NotificationPermissionPromptState(
                sdkInt = 35,
                granted = false,
                alreadyAsked = false,
            )
        seedSession("permission-first-send")
        advanceUntilIdle()

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        assertThat(viewModel.notificationPermissionRequests.value).isNotNull()
        assertThat(savedMessages).isEmpty()
        assertThat(protocolRequestCount).isEqualTo(0)
        assertThat(foregroundTrackedTasks).isEmpty()
        assertThat(viewModel.uiState.value.isGenerating).isFalse()
        assertThat(viewModel.uiState.value.status).isEqualTo(GenerationStatus.IDLE)
    }

    @Test
    fun `首次授权后恢复后台生成并清除权限请求`() = runTest {
        notificationPermissionState =
            com.promenar.nexara.background.generation.NotificationPermissionPromptState(
                sdkInt = 35,
                granted = false,
                alreadyAsked = false,
            )
        seedSession("permission-granted")
        advanceUntilIdle()
        viewModel.sendMessage("hello")
        advanceUntilIdle()
        val token = requireNotNull(viewModel.notificationPermissionRequests.value).taskId

        notificationPermissionState = notificationPermissionState!!.copy(
            granted = true,
            alreadyAsked = true,
        )
        viewModel.onNotificationPermissionResult(token, granted = true)
        advanceUntilIdle()

        assertThat(viewModel.notificationPermissionRequests.value).isNull()
        assertThat(savedMessages.map { it.first.role })
            .containsExactly(MessageRole.USER, MessageRole.ASSISTANT)
            .inOrder()
        assertThat(protocolRequestCount).isEqualTo(1)
        assertThat(foregroundTrackedTasks.map { it.sessionId })
            .containsExactly("permission-granted")
    }

    @Test
    fun `首次拒绝后仅前台生成且后续发送不重复提示不启动前台服务`() = runTest {
        notificationPermissionState =
            com.promenar.nexara.background.generation.NotificationPermissionPromptState(
                sdkInt = 35,
                granted = false,
                alreadyAsked = false,
            )
        seedSession("permission-denied")
        advanceUntilIdle()
        viewModel.sendMessage("first")
        advanceUntilIdle()
        val token = requireNotNull(viewModel.notificationPermissionRequests.value).taskId

        notificationPermissionState = notificationPermissionState!!.copy(alreadyAsked = true)
        viewModel.onNotificationPermissionResult(token, granted = false)
        advanceUntilIdle()

        assertThat(viewModel.notificationPermissionRequests.value).isNull()
        assertThat(protocolRequestCount).isEqualTo(1)
        assertThat(savedMessages).hasSize(2)
        assertThat(foregroundTrackedTasks).isEmpty()

        viewModel.sendMessage("second")
        advanceUntilIdle()

        assertThat(viewModel.notificationPermissionRequests.value).isNull()
        assertThat(protocolRequestCount).isEqualTo(2)
        assertThat(savedMessages).hasSize(4)
        assertThat(foregroundTrackedTasks).isEmpty()
    }

    @Test
    fun `取消或切换会话后过期权限token不能重放待发送消息`() = runTest {
        notificationPermissionState =
            com.promenar.nexara.background.generation.NotificationPermissionPromptState(
                sdkInt = 35,
                granted = false,
                alreadyAsked = false,
            )
        seedSession("permission-stale-A")
        advanceUntilIdle()
        viewModel.sendMessage("cancelled")
        advanceUntilIdle()
        val cancelledToken = requireNotNull(viewModel.notificationPermissionRequests.value).taskId

        viewModel.stopGeneration()
        viewModel.onNotificationPermissionResult(cancelledToken, granted = true)
        advanceUntilIdle()
        assertThat(savedMessages).isEmpty()
        assertThat(protocolRequestCount).isEqualTo(0)

        viewModel.sendMessage("switched")
        advanceUntilIdle()
        val switchedToken = requireNotNull(viewModel.notificationPermissionRequests.value).taskId
        seedSession("permission-stale-B")
        advanceUntilIdle()
        viewModel.onNotificationPermissionResult(switchedToken, granted = true)
        advanceUntilIdle()

        assertThat(viewModel.notificationPermissionRequests.value).isNull()
        assertThat(savedMessages).isEmpty()
        assertThat(protocolRequestCount).isEqualTo(0)
    }

    @Test
    fun `clearError不清除独立后台warning`() = runTest {
        seedSession("warning-clear")
        advanceUntilIdle()
        holdStreamOpen = true
        foregroundStartResult =
            com.promenar.nexara.background.generation.ForegroundStartResult.StartedWithoutNotifications

        viewModel.sendMessage("hello")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.backgroundWarning).isNotNull()

        viewModel.clearError()
        assertThat(viewModel.uiState.value.backgroundWarning).isNotNull()
    }

    @Test
    fun `后台promotion失败在终态竞态后仍显示同task停止错误且切session清理`() = runTest {
        seedSession("warning-A")
        advanceUntilIdle()
        holdStreamOpen = true
        viewModel.sendMessage("hello")
        advanceUntilIdle()
        val taskId = foregroundTrackedTasks.single().taskId

        foregroundFailures.tryEmit(
            com.promenar.nexara.background.generation.ForegroundServiceFailure(
                "stale",
                com.promenar.nexara.background.generation.ForegroundServiceFailureOutcome.GENERATION_STOPPED,
            ),
        )
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isNull()

        viewModel.stopGeneration()
        advanceUntilIdle()
        foregroundFailures.tryEmit(
            com.promenar.nexara.background.generation.ForegroundServiceFailure(
                taskId,
                com.promenar.nexara.background.generation.ForegroundServiceFailureOutcome.GENERATION_STOPPED,
            ),
        )
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isEqualTo(BACKGROUND_SERVICE_STOPPED_MESSAGE)

        seedSession("warning-B")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `停止错误遮挡普通错误时两次清理按展示层依次暴露`() = runTest {
        seedSession("layered-errors")
        advanceUntilIdle()
        holdStreamOpen = true
        viewModel.sendMessage("hello")
        advanceUntilIdle()
        val taskId = foregroundTrackedTasks.single().taskId
        foregroundFailures.tryEmit(
            com.promenar.nexara.background.generation.ForegroundServiceFailure(
                taskId,
                com.promenar.nexara.background.generation.ForegroundServiceFailureOutcome.GENERATION_STOPPED,
            ),
        )
        advanceUntilIdle()

        @Suppress("UNCHECKED_CAST")
        val ordinaryError = ChatViewModel::class.java.getDeclaredField("_error").run {
            isAccessible = true
            get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<String?>
        }
        ordinaryError.value = "new provider error"
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isEqualTo(BACKGROUND_SERVICE_STOPPED_MESSAGE)

        viewModel.clearError()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isEqualTo("new provider error")

        viewModel.clearError()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isNull()
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
        assertThat(foregroundTrackedTasks.map { it.sessionId }).containsExactly("A")

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
        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.generationNotice?.code)
            .isEqualTo("generation.failure.busy")
        assertThat(viewModel.uiState.value.generationNotice?.formatArgs).isEmpty()
        assertThat(protocolRequestCount).isEqualTo(1)
        assertThat(foregroundTrackedTasks.map { it.sessionId }).containsExactly("A")
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
            StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN)
        )

        viewModel.sendMessage("hello"); advanceUntilIdle()
        
         

        assertThat(viewModel.uiState.value.messages.any { it.content == "first response" }).isTrue()

        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("retry response"),
            StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN)
        )

        viewModel.retryLastMessage(); advanceUntilIdle()
        
         

        val messages = viewModel.uiState.value.messages
        val assistantMessages = messages.filter { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMessages.any { it.content == "retry response" }).isTrue()
        assertThat(savedMessages.count { it.first.role == MessageRole.USER }).isEqualTo(1)
        assertThat(savedMessages.count { it.first.role == MessageRole.ASSISTANT }).isEqualTo(1)
        assertThat(foregroundTrackedTasks).hasSize(2)
        
    }

    @Test
    fun `重试遇到全局Busy时恢复旧回复且不留下新占位`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession("retry-target")
        advanceUntilIdle()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("existing reply"),
            StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN),
        )
        viewModel.sendMessage("hello")
        advanceUntilIdle()

        seedSession("active")
        advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("active partial"))
        holdStreamOpen = true
        viewModel.sendMessage("keep running")
        advanceUntilIdle()

        viewModel.loadSession("retry-target")
        advanceUntilIdle()
        val requestsBeforeRetry = protocolRequestCount
        viewModel.retryLastMessage()
        advanceUntilIdle()

        val retryMessages = savedMessages
            .filter { it.second == "retry-target" }
            .map { it.first }
        assertThat(protocolRequestCount).isEqualTo(requestsBeforeRetry)
        assertThat(retryMessages.map { it.content })
            .containsExactly("hello", "existing reply")
            .inOrder()
        assertThat(retryMessages.count { it.role == MessageRole.ASSISTANT }).isEqualTo(1)
        assertThat(viewModel.uiState.value.generationNotice?.code)
            .isEqualTo("generation.failure.busy")

        viewModel.loadSession("active")
        advanceUntilIdle()
        viewModel.stopGeneration()
        advanceUntilIdle()
    }

    @Test
    fun `重试启动后路由失败仍保留旧回复并清理新占位`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession("retry-route")
        advanceUntilIdle()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("existing reply"),
            StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN),
        )
        viewModel.sendMessage("hello")
        advanceUntilIdle()

        forcedProviderFailure = ProviderResolution.Failure(
            ProviderResolutionError.PROVIDER_DISABLED,
            "gpt-4o",
        )
        viewModel.retryLastMessage()
        advanceUntilIdle()

        val retryMessages = savedMessages
            .filter { it.second == "retry-route" }
            .map { it.first }
        assertThat(retryMessages.map { it.content })
            .containsExactly("hello", "existing reply")
            .inOrder()
        assertThat(retryMessages.count { it.role == MessageRole.ASSISTANT }).isEqualTo(1)
        assertThat(viewModel.uiState.value.generationNotice?.code)
            .isEqualTo("generation.failure.invalid_request")
    }

    @Test
    fun `重试首次触发权限且取消等待时保留旧回复`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession("retry-permission")
        advanceUntilIdle()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("existing reply"),
            StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN),
        )
        viewModel.sendMessage("hello")
        advanceUntilIdle()
        assertThat(savedMessages.map { it.first.content })
            .containsExactly("hello", "existing reply")
            .inOrder()

        notificationPermissionState =
            com.promenar.nexara.background.generation.NotificationPermissionPromptState(
                sdkInt = 35,
                granted = false,
                alreadyAsked = false,
            )
        viewModel.retryLastMessage()
        advanceUntilIdle()

        assertThat(viewModel.notificationPermissionRequests.value).isNotNull()
        viewModel.stopGeneration()
        advanceUntilIdle()

        assertThat(savedMessages.map { it.first.content })
            .containsExactly("hello", "existing reply")
            .inOrder()
        assertThat(viewModel.uiState.value.messages.any { it.content == "existing reply" }).isTrue()
    }

    @Test
    fun `重试成功后删除旧回复失败时旧回复仍保留`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("first response"),
            StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN),
        )
        viewModel.sendMessage("hello"); advanceUntilIdle()
        val requestsBeforeRetry = protocolRequestCount
        failScopedDelete = true

        viewModel.retryLastMessage(); advanceUntilIdle()

        assertThat(protocolRequestCount).isEqualTo(requestsBeforeRetry + 1)
        assertThat(viewModel.uiState.value.messages.any { it.content == "first response" }).isTrue()
    }

    @Test
    fun sendMessage_withError_updatesErrorState() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(
            StreamChunk.Error(technical = "Something went wrong")
        )

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.generationNotice?.code)
            .isEqualTo("generation.failure.unknown")
        assertThat(viewModel.uiState.value.generationNotice?.formatArgs).isEmpty()
        assertThat(viewModel.uiState.value.generationNotice?.formatArgs?.joinToString())
            .doesNotContain("Something went wrong")
        
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
