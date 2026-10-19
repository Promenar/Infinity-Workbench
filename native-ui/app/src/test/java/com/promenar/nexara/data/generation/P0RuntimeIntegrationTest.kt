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
import com.promenar.nexara.domain.generation.GenerationPreparationOutcome
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
import io.mockk.spyk
import io.mockk.coVerify
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
    fun `首轮工具续轮和轮后均检查实际将发送的上下文`() = runTest {
        val fixture = fixture()
        val pipeline = spyk(ContextCompactionPipeline())

        ChatGenerationRunner(fixture.runtime(pipeline)).run(fixture.request) {}

        assertThat(fixture.prompts).hasSize(2)
        assertThat(fixture.prompts[1].messages.any { it.role == "tool" }).isTrue()
        coVerify(exactly = 3) { pipeline.compact(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `阈值触发先折叠再归档摘要且两类压缩只使用活跃模型并记录费用`() = runTest {
        val fixture = fixture(priorToolResults = listOf("旧工具事实".repeat(500), "最近一", "最近二", "最近三"), historicalTurn = true)
        fixture.preferences.edit().putString("preset_summary_model", "quick::different").commit()
        fixture.sessions.updateSessionInferenceParams("session", InferenceParams(maxTokens = 64, keepRecentTurns = 1, autoSummaryThreshold = 0.0))
        fixture.sessions.updateSession("session", mapOf("ragOptions" to RagOptions(enableMemory = true, enableDocs = false)))
        fixture.summaryResponse = PromptResponse("保留关键事实的历史摘要", usage = ProtocolUsage(7, 3, 10))
        fixture.rounds = { listOf(StreamChunk.TextDelta("回答"), StreamChunk.Completed(CompletionReason.END_TURN)) }

        withContext(Dispatchers.Default) { ChatGenerationRunner(fixture.runtime()).run(fixture.request) {} }

        assertThat(fixture.summaryPrompts).hasSize(2)
        assertThat(fixture.summaryPrompts.map { it.model }).containsExactly("p0-integration-model", "p0-integration-model")
        assertThat(fixture.resolvedModels).doesNotContain("quick::different")
        assertThat(fixture.postEvents).containsExactly("tool-summary", "archive", "history-summary").inOrder()
        val session = fixture.store.getSession("session")!!
        assertThat(session.summary).isEqualTo("保留关键事实的历史摘要")
        assertThat(session.messages.filter { it.id.startsWith("historical-") }.all { it.isArchived }).isTrue()
        assertThat(session.messages.first { it.id == "user" }.isArchived).isFalse()
        assertThat(fixture.messageRepository.getById("assistant")!!.tokens!!.summaryTotal).isEqualTo(20)
        assertThat(fixture.prompts.single().messages.first().content).contains("<history_summary>")
        assertThat(fixture.prompts.single().messages.none { it.role == "tool" }).isTrue()
    }

    @Test
    fun `历史摘要失败后轮后检查不重试且原消息保持未摘要`() = runTest {
        val fixture = fixture(priorToolResults = listOf("完整旧结果"), historicalTurn = true)
        fixture.sessions.updateSessionInferenceParams("session", InferenceParams(maxTokens = 64, keepRecentTurns = 1, autoSummaryThreshold = 0.0))
        fixture.summaryResponse = PromptResponse("", usage = ProtocolUsage(4, 0, 4))
        fixture.rounds = { listOf(StreamChunk.TextDelta("回答"), StreamChunk.Completed(CompletionReason.END_TURN)) }

        withContext(Dispatchers.Default) { ChatGenerationRunner(fixture.runtime()).run(fixture.request) {} }

        assertThat(fixture.summaryPrompts).hasSize(1)
        assertThat(fixture.store.getSession("session")!!.summary).isNull()
        assertThat(fixture.messageRepository.getBySession("session").none { it.isArchived }).isTrue()
        assertThat(fixture.prompts.single().messages.first { it.role == "tool" }.content).isEqualTo("完整旧结果")
        assertThat(fixture.messageRepository.getById("assistant")!!.tokens!!.summaryTotal).isEqualTo(4)
    }

    @Test
    fun `手动压缩忽略高阈值执行工具折叠和历史摘要且不创建生成消息`() = runTest {
        val fixture = fixture(priorToolResults = listOf("工具原始内容".repeat(200), "最近一", "最近二", "最近三"), historicalTurn = true)
        fixture.sessions.updateSessionInferenceParams("session", InferenceParams(maxTokens = 64, keepRecentTurns = 1, autoSummaryThreshold = 0.95))
        fixture.preferences.edit().putString("preset_summary_model", "quick::different").commit()
        fixture.summaryResponse = PromptResponse("手动摘要", usage = ProtocolUsage(7, 3, 10))
        for ((index, message) in fixture.messageRepository.getBySession("session").withIndex()) {
            fixture.messageRepository.update(message.copy(
                createdAt = 1000L + index,
                modelId = if (message.role == MessageRole.ASSISTANT) "old::model-a" else message.modelId,
                tokens = if (message.id == "assistant") TokenUsage(100, 50, 150) else message.tokens,
            ), "session")
        }
        fixture.rebuildFromRepositories()
        val beforeIds = fixture.messageRepository.getBySession("session").map { it.id }
        val startedAt = System.currentTimeMillis()

        val result = withContext(Dispatchers.Default) { fixture.runtime().compactNow("session") {} }

        assertThat(result.newlyFoldedToolResults).isEqualTo(1)
        assertThat(result.summarizedTurns).isEqualTo(1)
        assertThat(result.afterTokens).isLessThan(result.beforeTokens)
        assertThat(fixture.prompts).isEmpty()
        assertThat(fixture.summaryPrompts).hasSize(2)
        assertThat(fixture.resolvedModels).doesNotContain("quick::different")
        assertThat(fixture.messageRepository.getBySession("session").map { it.id }).isEqualTo(beforeIds)
        assertThat(fixture.messageRepository.getById("historical-result-0")!!.content).isEqualTo("工具原始内容".repeat(200))
        assertThat(fixture.messageRepository.getById("assistant")!!.tokens).isEqualTo(TokenUsage(100, 50, 150))
        val usageStore = com.promenar.nexara.data.repository.RolledBackSummaryUsageStore(fixture.preferences)
        val records = usageStore.withRecords { it.toList() }
        assertThat(records).hasSize(2)
        assertThat(records.map { it.modelId }.distinct()).containsExactly("local::p0-integration-model")
        assertThat(records.all { it.createdAt in startedAt..System.currentTimeMillis() }).isTrue()
        assertThat(records.sumOf { it.usage.input }).isEqualTo(14)
        assertThat(records.sumOf { it.usage.output }).isEqualTo(6)
        val statistics = com.promenar.nexara.data.repository.TokenStatsRepository(
            fixture.database.messageDao(), rolledBackSummaryUsageStore = usageStore,
        )
        val sessionUsage = statistics.getUsageBySession("session")
        assertThat(sessionUsage.inputTokens).isEqualTo(118L)
        assertThat(sessionUsage.outputTokens).isEqualTo(60L)
    }

    @Test
    fun `短工具结果无候选时模型不可用仍返回空手动结果`() = runTest {
        val fixture = fixture(priorToolResults = listOf("短一", "短二", "短三", "短四"))
        fixture.providerUnavailable = true

        val result = fixture.runtime().compactNow("session") {}

        assertThat(result.newlyFoldedToolResults).isEqualTo(0)
        assertThat(result.summarizedTurns).isEqualTo(0)
        assertThat(result.summaryFailed).isFalse()
        assertThat(fixture.summaryPrompts).isEmpty()
    }

    @Test
    fun `用量估算复用路由能力和工具技能组装且等于实际准备请求`() = runTest {
        for (type in listOf(ProtocolType.Local, ProtocolType.Google_VertexAI, ProtocolType.Google_Gemini)) {
            val fixture = fixture()
            fixture.providerProtocolType = type
            fixture.skillCatalog = { error("没有activate_skill时不得加载技能目录") }
            val runtime = fixture.runtime()
            runtime.prepare(fixture.request)
            assertThat(runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
            val field = DefaultChatGenerationRuntime::class.java.getDeclaredField("preparedPrompt").apply { isAccessible = true }
            val actual = field.get(runtime) as PromptRequest

            val estimate = runtime.estimateContext("session")

            assertThat(estimate.totalTokens).isEqualTo(ApproximateTokenEstimator.estimate(actual))
            assertThat(fixture.builtContexts.last().availableSkills).isEmpty()
            assertThat(actual.tools.orEmpty()).hasSize(1)
            assertThat(fixture.builtContexts.last().availableToolNames).isNotEmpty()
        }
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
        val fixture = fixture(priorToolResults = listOf(original, "最近结果一", "最近结果二", "最近结果三"))
        fixture.preferences.edit()
            .putInt("model_info_local::p0-integration-model_context", 4096)
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
        assertThat(PreparedPromptBudgetGate.evaluate(
            fixture.store.getSession("session")!!, fixture.prompts.single(), fixture.preferences, false,
            "local::p0-integration-model",
        )).isInstanceOf(com.promenar.nexara.data.document.ContextBudgetDecision.Allowed::class.java)
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
        historicalTurn: Boolean = false,
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
                inferenceParams = InferenceParams(maxTokens = 64, keepRecentTurns = 20), workspaceRootUuid = "root",
            ))
            val historicalUser = if (historicalTurn) "historical-user" else "user"
            fixture.messages.addMessage("session", Message(historicalUser, MessageRole.USER, "完成工具任务"))
            priorToolResults.forEachIndexed { index, content ->
                val assistantId = "historical-assistant-$index"
                val callId = "historical-call-$index"
                fixture.messages.addMessage("session", Message(
                    assistantId, MessageRole.ASSISTANT, "", tokens = TokenUsage(1, 1, 2), parentMessageId = historicalUser,
                    toolCalls = listOf(ToolCall(callId, "write_file", """{"path":"/history-$index.md"}""")),
                ))
                fixture.messages.addMessage("session", Message(
                    "historical-result-$index", MessageRole.TOOL, content,
                    toolCallId = callId, parentMessageId = assistantId, name = "write_file",
                ))
            }
            if (historicalTurn) fixture.messages.addMessage("session", Message("user", MessageRole.USER, "当前用户请求"))
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
        val resolvedModels = mutableListOf<String>()
        val postEvents = mutableListOf<String>()
        val builtContexts = mutableListOf<com.promenar.nexara.ui.chat.manager.ContextBuilderParams>()
        var providerProtocolType: ProtocolType = ProtocolType.Local
        var providerUnavailable = false
        var skillCatalog: () -> List<com.promenar.nexara.data.skills.AgentSkillMetadata> = { emptyList() }
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

        fun runtime(pipeline: ContextCompactionPipeline = ContextCompactionPipeline()): DefaultChatGenerationRuntime {
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
                    postEvents += if (request.messages.any { it.content.startsWith("你是一个信息压缩助手") }) "history-summary" else "tool-summary"
                    return summaryResponse
                }
                override fun cancel() = Unit
            }
            val provider = LlmProvider(protocol)
            val router = object : ProviderRequestRouter {
                override fun resolve(modelId: String): ProviderResolution {
                    resolvedModels += modelId
                    if (providerUnavailable) return ProviderResolution.Failure(
                        com.promenar.nexara.data.remote.ProviderResolutionError.MODEL_NOT_FOUND, modelId = modelId,
                    )
                    return ProviderResolution.Success(ResolvedProviderModel(
                        modelId, "p0-integration-model", "local", "Local",
                        UnifiedProviderConfig(providerProtocolType, "", "", "p0-integration-model"),
                    ))
                }
                override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = UnifiedLlmClient(
                    providerConfigResolver = { resolved.config }, protocolFactory = { protocol },
                )
            }
            val builder = mockk<ContextBuilder>()
            var contextRound = 0
            coEvery { builder.buildContext(any()) } coAnswers {
                builtContexts += firstArg<com.promenar.nexara.ui.chat.manager.ContextBuilderParams>()
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
                postProcessor = mockk<com.promenar.nexara.ui.chat.manager.PostProcessor>(relaxed = true) {
                    coEvery { archiveMessagesToRag(any(), any(), any(), any()) } coAnswers { postEvents += "archive" }
                }, memoryManager = null,
                summaryManager = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { provider }.historySummaryManager(provider),
                sessionManager = sessions, contentStrategy = DefaultChatGenerationContentStrategy(preferences, registry),
                ui = mockk(relaxed = true), outputStore = outputStore, interactionStore = interactions,
                compactionPipeline = pipeline, skillCatalog = skillCatalog,
            )
        }
    }
}
