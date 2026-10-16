package com.promenar.nexara.data.generation

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.model.InferenceParams
import com.promenar.nexara.data.model.LoopStatus
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.SessionOptions
import com.promenar.nexara.data.model.TokenUsage
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.json
import com.promenar.nexara.data.remote.ProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ResolvedProviderModel
import com.promenar.nexara.data.remote.UnifiedLlmClient
import com.promenar.nexara.data.remote.UnifiedProviderConfig
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolChoice
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.ProtocolUsage
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.MessageRepository
import com.promenar.nexara.data.repository.SessionRepository
import com.promenar.nexara.data.repository.ToolExecutionKey
import com.promenar.nexara.data.repository.ToolExecutionLedgerRepository
import com.promenar.nexara.data.repository.ToolExecutionOutcome
import com.promenar.nexara.data.repository.ToolInvocationIdentityFactory
import com.promenar.nexara.data.repository.ToolInvocationIdentityResolution
import com.promenar.nexara.data.repository.ToolOutputStore
import com.promenar.nexara.domain.generation.CompletionReason
import com.promenar.nexara.domain.generation.GenerationEvent
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationRuntimePolicy
import com.promenar.nexara.domain.repository.IAgentRepository
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.domain.usecase.AgentConfigResolver
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.ApprovalManager
import com.promenar.nexara.ui.chat.manager.ContextBuilder
import com.promenar.nexara.ui.chat.manager.ContextBuilderResult
import com.promenar.nexara.ui.chat.manager.MessageManager
import com.promenar.nexara.ui.chat.manager.SessionManager
import com.promenar.nexara.ui.chat.manager.ToolExecutor
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class P0RuntimeIntegrationTest {
    @get:Rule
    val temporaryFiles = TemporaryFolder()
    private val databases = mutableListOf<NexaraDatabase>()

    @After
    fun closeDatabases() {
        databases.forEach(NexaraDatabase::close)
    }

    @Test
    fun `不限半自动 prepare 后切全自动需等待确认且接受结果持久化`() = runTest {
        val fixture = fixture(mode = "semi", confirmed = false)
        fixture.onPrompt = { round, _ ->
            if (round == 1) fixture.sessions.updateSessionExecutionMode("session", "auto")
        }
        val events = mutableListOf<GenerationEvent>()
        val job = async { ChatGenerationRunner(fixture.runtime()).run(fixture.request) { events += it } }
        val confirmation = fixture.interactions.pending.first { it != null }!!

        assertThat(confirmation.kind).isEqualTo(GenerationInteractionKind.UNLIMITED_AUTO)
        assertThat(fixture.prompts).hasSize(1)
        assertThat(fixture.executedCalls).isEmpty()
        assertThat(fixture.messageRepository.getBySession("session").filter { it.role == MessageRole.TOOL }).isEmpty()
        assertThat(fixture.preferences.getBoolean(UnlimitedAutomationConsent.KEY, false)).isFalse()

        assertThat(fixture.interactions.respond(confirmation.id, true)).isTrue()
        job.await()

        assertThat(fixture.executedCalls).containsExactly("call-1")
        assertThat(fixture.messageRepository.getBySession("session").single { it.role == MessageRole.TOOL }.content)
            .isEqualTo("完整工具结果")
        val reopenedPreferences = fixture.context.getSharedPreferences(fixture.preferencesName, Context.MODE_PRIVATE)
        assertThat(reopenedPreferences.getBoolean(UnlimitedAutomationConsent.KEY, false)).isTrue()
        assertThat(UnlimitedAutomationConsent(reopenedPreferences).requiresConfirmation(true, "auto")).isFalse()
        assertThat(events.filterIsInstance<GenerationEvent.PhaseChanged>().last().phase).isEqualTo(GenerationPhase.COMPLETED)
    }

    @Test
    fun `无 Usage 估算落库后审批恢复用相同用户消息累计 token 并触发收尾`() = runTest {
        val fixture = fixture(mode = "manual")
        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
        assertThat(fixture.store.getSession("session")!!.loopStatus).isEqualTo(LoopStatus.WAITING_FOR_APPROVAL)
        assertThat(fixture.executedCalls).isEmpty()
        val firstAssistant = fixture.messageRepository.getById("assistant")!!
        val storedUsage = requireNotNull(firstAssistant.tokens)
        assertThat(storedUsage.estimated).isTrue()
        assertThat(storedUsage.total).isGreaterThan(0)
        val tokensJson = requireNotNull(fixture.database.messageDao().getById("assistant")!!.tokens)
        assertThat(json.decodeFromString<TokenUsage>(tokensJson)).isEqualTo(storedUsage)

        val approval = fixture.store.getSession("session")!!.approvalRequest!!
        val approvals = ApprovalManager(fixture.store, fixture.ledger, fixture.messages, fixture.sessionRepository)
        approvals.setCallbacks(null) { sessionId, messageId, allowed ->
            val calls = fixture.store.getSession(sessionId)!!.messages.first { it.id == messageId }.toolCalls!!
            fixture.executor.executeTools(sessionId, messageId, calls, allowed, fixture.advertisedTools)
        }
        approvals.resumeGeneration("session", approval)
        assertThat(fixture.executedCalls).containsExactly("call-1")
        fixture.preferences.edit().putInt("task_token_limit", storedUsage.total).commit()
        fixture.rebuildFromRepositories()
        fixture.messages.addMessage("session", Message("resumed", MessageRole.ASSISTANT, "", parentMessageId = "user"))
        fixture.prompts.clear()
        fixture.rounds = { listOf(StreamChunk.TextDelta("预算收尾"), StreamChunk.Completed(CompletionReason.END_TURN)) }
        val resumedRequest = fixture.request.copy(assistantMessageId = "resumed")
        val resumedEvents = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(fixture.runtime()).run(resumedRequest) { resumedEvents += it }

        val firstProgress = resumedEvents.filterIsInstance<GenerationEvent.ProgressChanged>().first().progress
        assertThat(firstProgress.tokens).isEqualTo(storedUsage.total.toLong())
        assertThat(firstProgress.estimated).isTrue()
        assertThat(fixture.prompts).hasSize(1)
        assertThat(fixture.prompts.single().toolChoice).isEqualTo(ProtocolToolChoice.NONE)
        assertThat(fixture.prompts.single().messages.first { it.role == "system" }.content).contains("Tool Budget Exhausted")
        assertThat(fixture.executedCalls).containsExactly("call-1")
        assertThat(fixture.messageRepository.getById("assistant")!!.tokens).isEqualTo(storedUsage)
        assertThat(fixture.messageRepository.getById("resumed")!!.content).isEqualTo("预算收尾")
    }

    @Test
    fun `大输出仅在发送请求中卸载且原始消息与私有完整输出保留`() = runTest {
        val output = ToolOutputStore(temporaryFiles.newFolder("tool-output"))
        val original = "H".repeat(6000) + "原始中段唯一标记" + "X".repeat(20000) + "T".repeat(6000)
        val fixture = fixture(outputStore = output)
        fixture.toolResult = original

        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}

        assertThat(fixture.prompts).hasSize(2)
        val sentResult = fixture.prompts[1].messages.single { it.role == "tool" }
        assertThat(sentResult.toolCallId).isEqualTo("call-1")
        assertThat(sentResult.content).contains("output_id=out_")
        assertThat(sentResult.content).contains("total_chars=${original.length}")
        assertThat(sentResult.content).doesNotContain("原始中段唯一标记")
        assertThat(sentResult.content.length).isLessThan(original.length)
        assertThat(fixture.store.getSession("session")!!.messages.single { it.role == MessageRole.TOOL }.content).isEqualTo(original)
        assertThat(fixture.messageRepository.getBySession("session").single { it.role == MessageRole.TOOL }.content).isEqualTo(original)
        val outputId = Regex("output_id=(out_[a-f0-9]{64})").find(sentResult.content)!!.groupValues[1]
        val restored = buildString {
            for (offset in 0 until original.length step 8000) append(output.read("session", outputId, offset, minOf(8000, original.length - offset)))
        }
        assertThat(restored).isEqualTo(original)
        val sentCalls = fixture.prompts[1].messages.filter { it.role == "assistant" }.flatMap { it.toolCalls.orEmpty() }
        assertThat(sentCalls.map { it.id }).containsExactly("call-1")
        assertThat(fixture.messageRepository.getById("assistant")!!.toolCalls!!.single().id).isEqualTo("call-1")
    }

    @Test
    fun `下一轮上下文挂起时取消仍保留已执行调用与结果的持久配对`() = runTest {
        val fixture = fixture()
        val continuationEntered = CompletableDeferred<Unit>()
        fixture.onContext = { round ->
            if (round == 2) {
                continuationEntered.complete(Unit)
                awaitCancellation()
            }
        }
        val events = mutableListOf<GenerationEvent>()
        val job = launch { ChatGenerationRunner(fixture.runtime()).run(fixture.request) { events += it } }
        continuationEntered.await()
        assertThat(fixture.executedCalls).containsExactly("call-1")

        job.cancel(CancellationException("用户停止"))
        job.join()

        assertThat(fixture.prompts).hasSize(1)
        val persisted = fixture.messageRepository.getBySession("session")
        assertThat(persisted.first { it.id == "assistant" }.toolCalls!!.map { it.id }).containsExactly("call-1")
        assertThat(persisted.first { it.id == "assistant" }.status).isEqualTo("cancelled")
        assertThat(persisted.single { it.role == MessageRole.TOOL }.toolCallId).isEqualTo("call-1")
        assertThat(persisted.single { it.role == MessageRole.TOOL }.content).isEqualTo("完整工具结果")
        assertThat(fixture.store.getSession("session")!!.messages.first { it.id == "assistant" }.toolCalls!!.single().id)
            .isEqualTo("call-1")
        assertThat(events.filterIsInstance<GenerationEvent.PhaseChanged>().last().phase).isEqualTo(GenerationPhase.CANCELLED)
    }

    @Test
    fun `空摘要的实报用量经真实上下文压缩持久化并使首个主请求进入收尾`() = runTest {
        val original = "旧工具结果".repeat(3000)
        val fixture = fixture(priorToolResults = listOf(original, "最近的结果"))
        fixture.preferences.edit()
            .putInt("model_info_local::p0-integration-model_context", 2048)
            .putInt("tool_context_keep_rounds", 1)
            .putInt("task_token_limit", 5000)
            .putString("preset_summary_model", "local::p0-integration-model")
            .commit()
        fixture.summaryResponse = PromptResponse("", usage = ProtocolUsage(4000, 1000, 5000))
        fixture.rounds = { listOf(StreamChunk.TextDelta("已按预算收尾"), StreamChunk.Completed(CompletionReason.END_TURN)) }
        val runtime = fixture.runtime()

        // 摘要路由使用真实 IO 线程；使用真实时钟避免测试调度器提前耗尽摘要超时。
        withContext(Dispatchers.Default) {
            ChatGenerationRunner(runtime).run(fixture.request) {}
        }

        assertThat(fixture.summaryPrompts).hasSize(1)
        assertThat(fixture.summaryPrompts.single().toolChoice).isEqualTo(ProtocolToolChoice.NONE)
        assertThat(runtime.additionalTokenUsage(fixture.request).tokens).isEqualTo(5000L)
        val usage = fixture.messageRepository.getById("assistant")!!.tokens!!
        assertThat(usage.summaryInput).isEqualTo(4000)
        assertThat(usage.summaryOutput).isEqualTo(1000)
        assertThat(usage.summaryTotal).isEqualTo(5000)
        assertThat(usage.summaryEstimated).isFalse()
        assertThat(usage.total).isAtLeast(5000)
        val rawTokens = fixture.database.messageDao().getById("assistant")!!.tokens!!
        assertThat(json.decodeFromString<TokenUsage>(rawTokens).summaryTotal).isEqualTo(5000)
        assertThat(fixture.prompts).hasSize(1)
        assertThat(fixture.prompts.single().toolChoice).isEqualTo(ProtocolToolChoice.NONE)
        val compressed = fixture.prompts.single().messages.first { it.role == "tool" }.content
        assertThat(compressed).contains("Truncated tool result")
        assertThat(compressed.length).isLessThan(original.length)
        assertThat(fixture.messageRepository.getById("historical-result-0")!!.content).isEqualTo(original)
        assertThat(fixture.executedCalls).isEmpty()
    }

    private suspend fun TestScope.fixture(
        mode: String = "auto",
        confirmed: Boolean = true,
        outputStore: ToolOutputStore? = null,
        priorToolResults: List<String> = emptyList(),
    ): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java).allowMainThreadQueries().build()
        databases += database
        return Fixture(this, context, database, outputStore).also { fixture ->
            fixture.preferences.edit().putInt("loop_limit", 0)
                .putBoolean(UnlimitedAutomationConsent.KEY, confirmed)
                .putStringSet("enabled_skills", setOf("write_file"))
                .putStringSet("known_builtin_skills", setOf("write_file"))
                .commit()
            fixture.sessions.addSession(Session(
                id = "session", agentId = "agent", modelId = "local::p0-integration-model", executionMode = mode,
                options = SessionOptions(toolsEnabled = true), ragOptions = RagOptions(enableMemory = false, enableDocs = false),
                inferenceParams = InferenceParams(maxTokens = 64, activeContextWindow = 100), workspaceRootUuid = "root",
            ))
            fixture.messages.addMessage("session", Message("user", MessageRole.USER, "完成工具任务"))
            priorToolResults.forEachIndexed { index, content ->
                val assistantId = "historical-assistant-$index"
                val callId = "historical-call-$index"
                fixture.messages.addMessage("session", Message(
                    assistantId, MessageRole.ASSISTANT, "", tokens = TokenUsage(1, 1, 2), parentMessageId = "user",
                    toolCalls = listOf(ToolCall(callId, "write_file", """{"path":"/history-$index.md"}""")),
                ))
                fixture.messages.addMessage("session", Message(
                    "historical-result-$index", MessageRole.TOOL, content,
                    toolCallId = callId, parentMessageId = assistantId, name = "write_file",
                ))
            }
            fixture.messages.addMessage("session", Message("assistant", MessageRole.ASSISTANT, "", parentMessageId = "user"))
        }
    }

    /** 只替换外部工具副作用；审批策略、台账、消息与请求构建均使用生产实现。 */
    private class Fixture(
        private val scope: TestScope,
        val context: Context,
        val database: NexaraDatabase,
        private val outputStore: ToolOutputStore?,
    ) {
        val preferencesName = "p0-integration-${UUID.randomUUID()}"
        val preferences: SharedPreferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val sessionRepository = SessionRepository(database.sessionDao(), database.messageDao())
        val messageRepository = MessageRepository(database.messageDao())
        val ledger = ToolExecutionLedgerRepository(database)
        var store = ChatStore()
            private set
        var messages = MessageManager(store, messageRepository, sessionRepository, scope)
            private set
        var sessions = SessionManager(store, sessionRepository)
            private set
        val interactions = GenerationInteractionStore()
        val prompts = mutableListOf<PromptRequest>()
        val executedCalls = mutableListOf<String>()
        val summaryPrompts = mutableListOf<PromptRequest>()
        var summaryResponse = PromptResponse("")
        var toolResult = "完整工具结果"
        var onPrompt: suspend (Int, PromptRequest) -> Unit = { _, _ -> }
        var onContext: suspend (Int) -> Unit = {}
        var rounds: (Int) -> List<StreamChunk> = { index ->
            if (index == 1) listOf(
                StreamChunk.ToolCallDelta("call-1", "write_file", """{"path":"/a.md"}""", 0),
                StreamChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call-1")),
            ) else listOf(StreamChunk.TextDelta("任务完成"), StreamChunk.Completed(CompletionReason.END_TURN))
        }
        val advertisedTools = listOf(ProtocolTool(
            function = ProtocolToolFunction("write_file", "写入文件", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""),
            risk = ToolRisk.FILE_WRITE, runtimeToolId = "write_file",
        ))
        val request = GenerationRequest("session", "assistant", "user", "完成工具任务", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED)
        val executor = mockk<ToolExecutor>()

        init {
            coEvery { executor.executeTools(any(), any(), any(), any(), any(), any()) } coAnswers {
                val sessionId = firstArg<String>()
                val messageId = secondArg<String>()
                val calls = thirdArg<List<ToolCall>>()
                val allowed = arg<Set<String>>(3)
                val definitions = arg<List<ProtocolTool>>(4)
                for (call in calls) {
                    val key = ToolExecutionKey(sessionId, messageId, call.id)
                    val identity = ledger.invocationIdentity(key) ?: (ToolInvocationIdentityFactory.fromPreparedToolCall(
                        call, definitions.single { it.function.name == call.name }, call.id !in allowed,
                    ) as ToolInvocationIdentityResolution.Valid).identity
                    ledger.register(key, identity)
                    if (call.id in allowed && ledger.claim(key, identity)) {
                        executedCalls += call.id
                        val result = ledger.finishWithResult(key, call.name, toolResult, null, ToolExecutionOutcome.Succeeded)
                        result?.let { messages.mirrorPersistedMessage(sessionId, it) }
                    }
                }
            }
            coEvery { executor.rejectTools(any(), any(), any(), any()) } coAnswers {
                val sessionId = firstArg<String>()
                val messageId = secondArg<String>()
                for (call in thirdArg<List<ToolCall>>()) messages.addMessage(sessionId, Message(
                    id = "rejected-$messageId-${call.id}", role = MessageRole.TOOL, content = arg<String>(3),
                    toolCallId = call.id, parentMessageId = messageId, name = call.name,
                ))
            }
        }

        suspend fun rebuildFromRepositories() {
            val restored = requireNotNull(sessionRepository.getById("session"))
            store = ChatStore().apply { upsertSession(restored) }
            messages = MessageManager(store, messageRepository, sessionRepository, scope)
            sessions = SessionManager(store, sessionRepository)
        }

        fun runtime(): DefaultChatGenerationRuntime {
            val protocol = object : LlmProtocol {
                override val protocolType = ProtocolType.Local
                override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
                    prompts += request
                    val round = prompts.size
                    onPrompt(round, request)
                    rounds(round).forEach { emit(it) }
                }
                override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
                    summaryPrompts += request
                    return summaryResponse
                }
                override fun cancel() = Unit
            }
            val provider = LlmProvider(protocol)
            val router = object : ProviderRequestRouter {
                override fun resolve(modelId: String) = ProviderResolution.Success(ResolvedProviderModel(
                    modelId, "p0-integration-model", "local", "Local",
                    UnifiedProviderConfig(ProtocolType.Local, "", "", "p0-integration-model"),
                ))
                override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("本地协议不创建远端客户端")
            }
            val builder = mockk<ContextBuilder>()
            var contextRound = 0
            coEvery { builder.buildContext(any()) } coAnswers {
                onContext(++contextRound)
                ContextBuilderResult("", "", emptyList(), emptyList(), null, "执行用户任务")
            }
            val registry = mockk<SkillRegistry>()
            every { registry.getAllTools(any()) } returns advertisedTools
            return DefaultChatGenerationRuntime(
                settings = preferences, applicationScope = scope, store = store,
                agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
                configResolver = AgentConfigResolver(preferences),
                routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(scope.testScheduler)),
                contextBuilder = builder, messageManager = messages,
                localProviderFactory = { provider }, provider = provider, toolLedger = ledger, toolExecutor = executor,
                postProcessor = mockk(relaxed = true), memoryManager = null, summaryManager = mockk(relaxed = true),
                sessionManager = sessions, contentStrategy = DefaultChatGenerationContentStrategy(preferences, registry),
                ui = mockk(relaxed = true), outputStore = outputStore, interactionStore = interactions,
            )
        }
    }
}
