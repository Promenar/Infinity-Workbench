package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.data.local.db.dao.stubUpdateWith
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.RagRetrievalTrace
import com.promenar.nexara.data.model.RagUsage
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.toEntity
import com.promenar.nexara.data.remote.ProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ResolvedProviderModel
import com.promenar.nexara.data.remote.UnifiedLlmClient
import com.promenar.nexara.data.remote.UnifiedProviderConfig
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.rag.EmbeddingClient
import com.promenar.nexara.data.rag.MemoryManager
import com.promenar.nexara.data.rag.MemoryManagerRagAdapter
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.repository.MessageRepository
import com.promenar.nexara.domain.generation.CompletionReason
import com.promenar.nexara.domain.generation.GenerationPreparationOutcome
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationRuntimePolicy
import com.promenar.nexara.domain.generation.GenerationSnapshot
import com.promenar.nexara.domain.generation.GenerationToolCall
import com.promenar.nexara.domain.generation.GenerationToolDecision
import com.promenar.nexara.domain.repository.IAgentRepository
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.domain.usecase.AgentConfigResolver
import com.promenar.nexara.ui.chat.ChatState
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.hasRagArtifacts
import com.promenar.nexara.ui.chat.manager.ContextBuilder
import com.promenar.nexara.ui.chat.manager.MessageManager
import com.promenar.nexara.ui.chat.manager.RagProvider
import com.promenar.nexara.ui.chat.manager.RagRetrievalResult
import com.promenar.nexara.ui.chat.manager.SessionManager
import com.promenar.nexara.ui.chat.selectRagActiveMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultChatGenerationRuntimeRagTraceTest {
    @Test
    fun `未选择嵌入模型时静默跳过检索且回复不显示检索步骤`() = runTest {
        val embedding = mockk<EmbeddingClient>()
        every { embedding.isConfigured } returns false
        every { embedding.hasLocalFallback } returns false
        every { embedding.diagnosticMessage() } returns "未配置嵌入模型"
        val memoryManager = MemoryManager(
            vectorStore = mockk(),
            keywordSearcher = mockk(),
            graphStore = mockk(),
            embeddingClient = embedding,
        )
        val fixture = fixture(MemoryManagerRagAdapter(memoryManager))

        fixture.buildAndEnterProvider()

        fixture.assertNoRetrievalStep()
        assertThat(fixture.networkEntries.single().last().ragMetadata).isNull()
    }

    @Test
    fun `未命中且没有引用时重载后保留检索入口`() = runTest {
        val trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_EMPTY, searchTimeMs = 7)
        val fixture = fixture(RecordingRagProvider(RagRetrievalResult("", emptyList(), null, trace)))

        fixture.buildAndEnterProvider()

        fixture.assertPersistedTrace(trace)
    }

    @Test
    fun `命中引用的召回重排轨迹完整通过消息JSON重载`() = runTest {
        val trace = RagRetrievalTrace(
            outcome = RagRetrievalTrace.OUTCOME_HIT,
            recallCount = 3,
            selectedCount = 1,
            scoring = RagRetrievalTrace.SCORING_HYBRID,
            rerank = RagRetrievalTrace.RERANK_API,
            rerankModel = "reranker",
            searchTimeMs = 9,
            rerankTimeMs = 4,
            documentCount = 1,
        )
        val reference = RagReference("r1", "检索正文", "产品文档")
        val fixture = fixture(
            RecordingRagProvider(RagRetrievalResult("检索正文", listOf(reference), RagUsage(12), trace)),
        )

        fixture.buildAndEnterProvider()

        fixture.assertPersistedTrace(trace)
        val reloaded = requireNotNull(fixture.repository.getById("assistant"))
        assertThat(reloaded.ragReferences).containsExactly(reference)
        assertThat(reloaded.ragMetadata?.totalTokens).isEqualTo(12)
    }

    @Test
    fun `检索异常转为失败轨迹且回复保留检索入口`() = runTest {
        val fixture = fixture(RecordingRagProvider(failure = IllegalStateException("检索服务不可用")))

        fixture.buildAndEnterProvider()

        fixture.assertPersistedTrace(RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_FAILED))
    }

    @Test
    fun `检索开启但Provider缺失时静默跳过`() = runTest {
        val fixture = fixture(provider = null)

        fixture.buildAndEnterProvider()

        fixture.assertNoRetrievalStep()
    }

    @Test
    fun `检索关闭时不调用Provider且重载后没有检索入口`() = runTest {
        val provider = RecordingRagProvider(
            RagRetrievalResult("", emptyList(), null, RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_EMPTY)),
        )
        val fixture = fixture(provider, enabled = false)

        fixture.buildAndEnterProvider()

        assertThat(provider.calls).isEqualTo(0)
        val reloaded = requireNotNull(fixture.repository.getById("assistant"))
        assertThat(reloaded.ragMetadata).isNull()
        assertThat(reloaded.hasRagArtifacts()).isFalse()
        assertThat(fixture.store.getSession("session")?.messages?.last()?.hasRagArtifacts()).isFalse()
    }

    @Test
    fun `工具续轮复用检索且轨迹只归属首条回复并可被页面选中`() = runTest {
        val trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_EMPTY)
        val provider = RecordingRagProvider(RagRetrievalResult("", emptyList(), null, trace))
        val fixture = fixture(provider)
        fixture.buildAndEnterProvider()
        val toolCall = GenerationToolCall("call-1", "calculator", "{}")
        fixture.runtime.persist(fixture.request, GenerationSnapshot(content = "调用计算器", toolCalls = listOf(toolCall)))

        assertThat(fixture.runtime.handleTools(fixture.request, listOf(toolCall)))
            .isEqualTo(GenerationToolDecision.CONTINUE)
        val continuationId = fixture.runtime.prepareContinuation(fixture.request)
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        fixture.runtime.stream(fixture.request, attempt = 0).toList()

        assertThat(provider.calls).isEqualTo(1)
        val reloaded = fixture.repository.getBySession("session")
        assertThat(reloaded.filter { it.hasRagArtifacts() }.map { it.id }).containsExactly("assistant")
        assertThat(reloaded.single { it.id == "assistant" }.ragMetadata?.trace).isEqualTo(trace.withSessionScopes())
        assertThat(reloaded.single { it.id == continuationId }.ragMetadata).isNull()
        assertThat(selectRagActiveMessage(reloaded.filter { it.role == MessageRole.ASSISTANT })?.id)
            .isEqualTo("assistant")
        assertThat(fixture.networkEntries).hasSize(2)
    }

    @Test
    fun `检索取消原样上抛且不持久化失败轨迹或进入模型`() = runTest {
        val cancellation = CancellationException("检索取消")
        val fixture = fixture(RecordingRagProvider(failure = cancellation))
        fixture.runtime.prepare(fixture.request)

        val caught = runCatching { fixture.runtime.buildContext(fixture.request) }.exceptionOrNull()

        assertThat(caught).isSameInstanceAs(cancellation)
        assertThat(fixture.networkEntries).isEmpty()
        val reloaded = requireNotNull(fixture.repository.getById("assistant"))
        assertThat(reloaded.ragMetadata).isNull()
        assertThat(reloaded.isError).isFalse()
        assertThat(reloaded.hasRagArtifacts()).isFalse()
    }

    private class RecordingRagProvider(
        private val result: RagRetrievalResult = RagRetrievalResult("", emptyList(), null),
        private val failure: Throwable? = null,
    ) : RagProvider {
        var calls = 0
            private set

        override suspend fun retrieveContext(
            query: String,
            sessionId: String,
            options: RagOptions,
            onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)?,
        ): RagRetrievalResult {
            calls++
            failure?.let { throw it }
            return result
        }
    }

    private data class Fixture(
        val runtime: DefaultChatGenerationRuntime,
        val request: GenerationRequest,
        val repository: MessageRepository,
        val store: ChatStore,
        val networkEntries: List<List<Message>>,
    ) {
        suspend fun buildAndEnterProvider() {
            runtime.prepare(request)
            assertThat(runtime.buildContext(request)).isEqualTo(GenerationPreparationOutcome.Ready)
            runtime.stream(request, attempt = 0).toList()
        }

        suspend fun assertNoRetrievalStep() {
            val reloaded = requireNotNull(repository.getById("assistant"))
            assertThat(reloaded.ragMetadata).isNull()
            assertThat(reloaded.ragReferences).isNull()
            assertThat(reloaded.hasRagArtifacts()).isFalse()
            assertThat(store.getSession("session")?.messages?.last()?.hasRagArtifacts()).isFalse()
        }

        suspend fun assertPersistedTrace(provided: RagRetrievalTrace) {
            val expected = provided.withSessionScopes()
            val reloaded = requireNotNull(repository.getById("assistant"))
            assertThat(reloaded.ragMetadata?.trace).isEqualTo(expected)
            assertThat(reloaded.hasRagArtifacts()).isTrue()
            assertThat(selectRagActiveMessage(listOf(reloaded))?.id).isEqualTo("assistant")
            assertThat(store.getSession("session")?.messages?.last()?.ragMetadata?.trace).isEqualTo(expected)
        }
    }

    private fun TestScope.fixture(provider: RagProvider?, enabled: Boolean = true): Fixture {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } returns emptySet()
        every { settings.getString(any(), any()) } returns ""
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val session = Session(
            id = "session",
            agentId = "agent",
            modelId = "local::model",
            executionMode = "auto",
            ragOptions = RagOptions(enableMemory = enabled, enableDocs = enabled),
            messages = listOf(
                Message("user", MessageRole.USER, "问题"),
                Message("assistant", MessageRole.ASSISTANT, "", parentMessageId = "user"),
            ),
        )
        val store = ChatStore().apply { update { ChatState(sessions = listOf(session)) } }
        val entities = session.messages.associateTo(linkedMapOf()) { it.id to it.toEntity(session.id) }
        val dao = mockk<MessageDao>()
        coEvery { dao.getById(any()) } answers { entities[firstArg()] }
        coEvery { dao.getBySession(any()) } answers {
            val sessionId = firstArg<String>()
            entities.values.filter { it.sessionId == sessionId }
        }
        coEvery { dao.insert(any()) } answers {
            val entity = firstArg<MessageEntity>()
            entities[entity.id] = entity
        }
        coEvery { dao.update(any()) } answers {
            val entity = firstArg<MessageEntity>()
            if (entity.id in entities) {
                entities[entity.id] = entity
                1
            } else 0
        }
        dao.stubUpdateWith()
        val repository = MessageRepository(dao)
        val sessionRepository = object : ISessionRepository {
            override suspend fun create(session: Session) = Unit
            override suspend fun updatePartial(id: String, updates: Map<String, Any?>) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun getById(id: String): Session? = store.getSession(id)
            override suspend fun getAll(): List<Session> = store.state.value.sessions
        }
        val manager = MessageManager(store, repository, sessionRepository, this)
        val networkEntries = mutableListOf<List<Message>>()
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.Local
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
                networkEntries += repository.getBySession(session.id)
                return flowOf(StreamChunk.Completed(CompletionReason.END_TURN))
            }

            override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
            override fun cancel() = Unit
        }
        val llmProvider = LlmProvider(protocol)
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(
                    modelId, "model", "local", "本地",
                    UnifiedProviderConfig(ProtocolType.Local, "", "", "model"),
                ),
            )

            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("本地协议无需客户端")
        }
        val calculator = ProtocolTool(
            function = ProtocolToolFunction("calculator", "计算", "{\"type\":\"object\",\"properties\":{}}"),
            risk = ToolRisk.SAFE_READ,
            runtimeToolId = "calculator",
        )
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = ContextBuilder(ragProvider = provider),
            messageManager = manager,
            localProviderFactory = { llmProvider },
            provider = llmProvider,
            toolLedger = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = SessionManager(store, sessionRepository),
            contentStrategy = DefaultChatGenerationContentStrategy(settings, null, SessionToolResolver { listOf(calculator) }),
            ui = mockk(relaxed = true),
        )
        val request = GenerationRequest(
            session.id, "assistant", "user", "问题", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )
        return Fixture(runtime, request, repository, store, networkEntries)
    }
}

/** 轨迹另记录本次实际执行的档位；测试会话按默认选项检索记忆与文档、未开启图谱。 */
private fun RagRetrievalTrace.withSessionScopes() = copy(
    memoryScope = com.promenar.nexara.data.model.ConversationScope.SESSION,
    documentScope = com.promenar.nexara.data.model.DocumentScope.KNOWLEDGE_BASE,
)
