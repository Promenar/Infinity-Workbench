package com.promenar.infinity.data.subagent

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.agent.AgentSubagentModelPolicy
import com.promenar.infinity.data.local.db.Converters
import com.promenar.infinity.data.generation.ChatProviderRouteGate
import com.promenar.infinity.data.generation.DefaultSessionToolResolver
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.catalog.*
import com.promenar.infinity.data.remote.protocol.*
import com.promenar.infinity.data.remote.ProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ProviderResolutionError
import com.promenar.infinity.data.remote.ResolvedProviderModel
import com.promenar.infinity.data.repository.ToolDefinitionDigestResolution
import com.promenar.infinity.data.repository.ToolInvocationIdentityFactory
import com.promenar.infinity.domain.generation.CompletionReason
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.repository.*
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import com.promenar.infinity.ui.chat.manager.skills.AgentTaskSkill
import com.promenar.infinity.ui.common.toModelSelectionUiModel
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test

/** 子代理模型参数通过真实工具入口校验，非法批次不能进入启动器。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentModelPolicyTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "parent"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }

    @Test fun `工具模式始终声明字符串模型且不使用枚举`() {
        val skill = AgentTaskSkill { error("不能启动") }
        val schema = Json.parseToJsonElement(skill.parametersSchema).jsonObject
        val properties = schema["properties"]!!.jsonObject["tasks"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject
        assertThat(properties["model"]?.jsonObject?.get("type")?.jsonPrimitive?.content).isEqualTo("string")
        assertThat(properties["model"]?.jsonObject?.containsKey("enum")).isFalse()
    }

    @Test fun `关闭选模时第二个任务指定模型则整个批次拒绝且说明原因`() = runTest {
        var launches = 0
        val skill = AgentTaskSkill { launches++; error("不能启动") }
        val args = Json.parseToJsonElement("""{"tasks":[{"description":"一","prompt":"一"},{"description":"二","prompt":"二","model":"other"}]}""").jsonObject
        val result = skill.execute(args, context)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).isEqualTo("Task 2: model selection is disabled for this agent. Omit \"model\" to use the default subagent model.")
        assertThat(launches).isEqualTo(0)
    }

    @Test fun `解析按任务模型默认模型会话模型排序并保留来源`() = runTest {
        val f = Fixture(this)
        f.policy = AgentSubagentModelPolicy("default", true, listOf("task"))
        val selected = f.launcher.launch(context, "task", listOf(spec("task"))).single()
        val default = f.launcher.launch(context, "default", listOf(spec())).single()
        f.policy = AgentSubagentModelPolicy(defaultModelId = "  ")
        val inherited = f.launcher.launch(context, "parent", listOf(spec())).single()
        assertThat(selected.modelId).isEqualTo("task")
        assertThat(selected.modelChosenByAgent).isTrue()
        assertThat(default.modelId).isEqualTo("default")
        assertThat(default.modelChosenByAgent).isFalse()
        assertThat(inherited.modelId).isEqualTo("parent-model")
        assertThat(inherited.modelChosenByAgent).isFalse()
        runCurrent()
        assertThat(f.requested).containsExactly("task", "default", "parent-model")
    }

    @Test fun `有独立模型时不要求父模型可用且全部为空仍拒绝`() = runTest {
        val f = Fixture(this)
        f.parentModel = null
        f.policy = AgentSubagentModelPolicy(defaultModelId = "default")
        assertThat(f.launcher.launch(context, "default", listOf(spec())).single().modelId).isEqualTo("default")
        f.policy = AgentSubagentModelPolicy()
        assertThat(runCatching { f.launcher.launch(context, "none", listOf(spec())) }.exceptionOrNull()?.message).isEqualTo("会话没有可用模型")
    }

    @Test fun `允许选模时非法列表项使整个批次拒绝合法列表项实际执行`() = runTest {
        val f = Fixture(this)
        f.policy = AgentSubagentModelPolicy("default", true, listOf("a", "b"))
        val rejected = f.skill.execute(args("a", "x"), context)
        assertThat(rejected.status).isEqualTo("error")
        assertThat(rejected.content).isEqualTo("Task 2: model \"x\" is not in this agent's allowed list: a, b.")
        assertThat(f.store.all()).isEmpty()
        assertThat(f.requested).isEmpty()
        val accepted = f.skill.execute(args(" a "), context)
        assertThat(accepted.status).isEqualTo("success")
        assertThat(f.requested).containsExactly("a")
        assertThat(f.store.all().single().runs.single().modelChosenByAgent).isTrue()
    }

    @Test fun `空字符串视为未传空可选列表拒绝任何显式模型`() = runTest {
        val f = Fixture(this)
        assertThat(f.skill.execute(args("  "), context).status).isEqualTo("success")
        assertThat(f.requested).containsExactly("parent-model")
        f.policy = AgentSubagentModelPolicy(allowPerTaskModel = true)
        val rejected = f.skill.execute(args("a"), context)
        assertThat(rejected.status).isEqualTo("error")
        assertThat(rejected.content).contains("allowed list: (none)")
    }

    @Test fun `明确不支持工具的任务模型拒绝且默认模型失败没有回退`() = runTest {
        val f = Fixture(this)
        f.unsupported += "no-tools"
        f.policy = AgentSubagentModelPolicy("no-tools", true, listOf("no-tools"))
        val rejected = f.skill.execute(args("no-tools"), context)
        assertThat(rejected.status).isEqualTo("error")
        assertThat(rejected.content).contains("does not support tool calling")
        assertThat(f.store.all()).isEmpty()
        val run = f.launcher.launch(context, "default", listOf(spec()), background = false).single()
        assertThat(run.status).isEqualTo(SubagentStatus.FAILED)
        assertThat(run.error).contains("no-tools")
        assertThat(run.error).contains("不支持工具调用")
        assertThat(run.error).contains("没有改用其它模型")
        assertThat(f.requested).isEmpty()
    }

    @Test fun `默认和任务模型不可用时失败并只请求选中模型`() = runTest {
        for (explicit in listOf(false, true)) {
            val f = Fixture(this)
            f.policy = AgentSubagentModelPolicy("missing", true, listOf("missing"))
            f.unavailable += "missing"
            val run = f.launcher.launch(context, "missing", listOf(spec(if (explicit) "missing" else null)), background = false).single()
            assertThat(run.status).isEqualTo(SubagentStatus.FAILED)
            assertThat(run.modelChosenByAgent).isEqualTo(explicit)
            assertThat(run.error).contains("missing")
            assertThat(run.error).contains("没有改用其它模型")
            assertThat(f.requested).containsExactly("missing")
        }
    }

    @Test fun `精确路由失败时不构建其它客户端且本地化错误包含实际模型`() = runTest {
        val requested = mutableListOf<String>()
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution {
                requested += modelId
                return ProviderResolution.Failure(ProviderResolutionError.MODEL_NOT_FOUND, modelId)
            }
            override fun createClient(resolved: ResolvedProviderModel): com.promenar.infinity.data.remote.UnifiedLlmClient = error("不能构建客户端")
        }
        val models = RoutedSubagentModelClient(ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler))) { error("不能回退到本地模型") }
        val f = Fixture(this)
        val launcher = SubagentLauncher(
            SubagentRunner(models, NoTools, SubagentApprovalBroker(), modelUnavailableText = { "unavailable: $it; no fallback" }),
            f.store, { Session(it, "agent") }, { "parent-model" }, applicationScope = backgroundScope,
            modelPolicyFor = { AgentSubagentModelPolicy(defaultModelId = "provider::missing") },
        )
        val result = launcher.launch(context, "routed", listOf(spec()), background = false).single()
        assertThat(result.status).isEqualTo(SubagentStatus.FAILED)
        assertThat(result.error).isEqualTo("unavailable: provider::missing; no fallback")
        assertThat(requested).containsExactly("provider::missing")
    }

    @Test fun `恢复从磁盘沿用模型与来源而不读取改变后的策略`() = runTest {
        for (explicit in listOf(false, true)) {
            val f = Fixture(this)
            f.policy = AgentSubagentModelPolicy("original", true, listOf("original"))
            val original = f.launcher.launch(context, "first", listOf(spec(if (explicit) "original" else null)), background = false).single()
            f.policy = AgentSubagentModelPolicy("changed")
            f.parentModel = "changed-parent"
            val restarted = f.launcher(SubagentRunStore(f.root, UnconfinedTestDispatcher(testScheduler)),
                policy = { error("恢复不能读取模型策略") })
            restarted.send(context.sessionId, original.id, "补充核对")
            runCurrent()
            val resumed = restarted.read(context.sessionId, original.id)
            assertThat(resumed.status).isEqualTo(SubagentStatus.COMPLETED)
            assertThat(resumed.modelId).isEqualTo("original")
            assertThat(resumed.modelChosenByAgent).isEqualTo(explicit)
            assertThat(f.requested).containsExactly("original", "original").inOrder()
        }
    }

    @Test fun `同一会话不同模型的用量与上下文窗口按实际模型归属`() = runTest {
        val f = Fixture(this)
        f.launcher.launch(context, "usage", listOf(spec("a"), spec("b")), background = false)
        val stats = SubagentTokenStatsRepository(EmptyStats, f.store)
        val usage = stats.getUsageByModel().associate { it.modelId to it.usage }
        assertThat(usage.keys).containsExactly("a", "b")
        assertThat(usage.getValue("a").totalTokens).isEqualTo(15)
        assertThat(usage.getValue("b").totalTokens).isEqualTo(30)
        assertThat(stats.getUsageBySession(context.sessionId).totalTokens).isEqualTo(45)
        assertThat(f.windows).containsAtLeast("a", "b")
    }

    @Test fun `旧检索JSON用默认策略新策略经真实转换器往返一致`() {
        val legacy = Converters.toRetrievalConfig("""{"memoryLimit":3,"enableDocs":false}""")!!
        assertThat(legacy.subagentModels).isEqualTo(AgentSubagentModelPolicy())
        assertThat(legacy.memoryLimit).isEqualTo(3)
        val config = AgentRetrievalConfig(subagentModels = AgentSubagentModelPolicy("provider::a", true, listOf("provider::a", "provider::b")))
        assertThat(Converters.toRetrievalConfig(Converters.fromRetrievalConfig(config))).isEqualTo(config)
        assertThat(Agent("id", "name").subagentModelPolicy).isEqualTo(AgentSubagentModelPolicy())
        assertThat(Agent("id", "name", retrievalConfig = config).subagentModelPolicy).isEqualTo(config.subagentModels)
        val run = SubagentRun(0, description = "任务", prompt = "任务", toolProfile = SubagentToolProfile.INHERIT,
            modelId = "a", maxSteps = 0, startedAt = 0)
        val encoded = Json.encodeToJsonElement(SubagentRun.serializer(), run).jsonObject
        assertThat(encoded.containsKey("modelChosenByAgent")).isFalse()
        assertThat(Json.decodeFromJsonElement(SubagentRun.serializer(), encoded).modelChosenByAgent).isFalse()
    }

    @Test fun `工具描述按策略广告ID与名称且执行改写一致保留原始指纹`() {
        val raw = AgentTaskSkill { error("不能启动") }.toProtocolTool()
        val digest = (ToolInvocationIdentityFactory.definitionDigest(raw) as ToolDefinitionDigestResolution.Valid).digest
        val disabled = SubagentModelToolDefinitions.forSession(raw, AgentSubagentModelPolicy())
        assertThat(modelDescription(disabled)).contains("Do not pass")
        assertThat(disabled.function.description).contains("default subagent model")
        assertThat(disabled.baseDefinitionDigest).isEqualTo(digest)
        val policy = AgentSubagentModelPolicy("default", true, listOf("a", "b"))
        val enabled = SubagentModelToolDefinitions.forSession(raw, policy) { if (it == "a") "模型 A" else null }
        assertThat(modelDescription(enabled)).contains("a (模型 A)")
        assertThat(modelDescription(enabled)).contains("b")
        assertThat(modelDescription(enabled)).contains("Omit")
        assertThat(enabled.baseDefinitionDigest).isEqualTo(digest)
        assertThat(SubagentModelToolDefinitions.forSession(enabled, policy) { if (it == "a") "模型 A" else null }).isEqualTo(enabled)
        assertThat(SubagentModelToolDefinitions.forExecution(raw, enabled)).isEqualTo(enabled)
        assertThat(SubagentModelToolDefinitions.forExecution(raw, disabled)).isEqualTo(disabled)
        assertThat(ToolInvocationIdentityFactory.definitionDigest(enabled)).isNotEqualTo(ToolInvocationIdentityFactory.definitionDigest(disabled))
        val changed = raw.copy(function = raw.function.copy(description = "changed"))
        assertThat(ToolInvocationIdentityFactory.definitionDigest(SubagentModelToolDefinitions.forExecution(changed, enabled)))
            .isNotEqualTo(ToolInvocationIdentityFactory.definitionDigest(enabled))
        val changedParameter = raw.copy(function = raw.function.copy(parameters = raw.function.parameters.replace(
            "Model selection is disabled for this agent.", "Changed raw model description.",
        )))
        assertThat(ToolInvocationIdentityFactory.definitionDigest(SubagentModelToolDefinitions.forExecution(changedParameter, enabled)))
            .isNotEqualTo(ToolInvocationIdentityFactory.definitionDigest(enabled))
        assertThat(SubagentModelToolDefinitions.forSession(raw.copy(sourceId = "mcp"), policy).baseDefinitionDigest).isNull()
    }

    @Test fun `会话解析器读取所属智能体策略并保持执行定义一致`() {
        val skill = AgentTaskSkill { error("不能启动") }
        val registry = object : SkillRegistry {
            override fun getSkill(name: String) = skill.takeIf { it.name == name }
            override fun getAllSkills() = listOf(skill)
            override fun getAllTools(allowedIds: List<String>?) = listOf(skill.toProtocolTool())
        }
        val settings = java.lang.reflect.Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, arguments ->
            when (method.name) {
                "getStringSet" -> null
                "getBoolean" -> arguments!![1]
                else -> error("不应调用设置方法 ${method.name}")
            }
        } as SharedPreferences
        val resolver = DefaultSessionToolResolver(settings, registry, subagentModelPolicy = { session ->
            if (session.agentId == "enabled") AgentSubagentModelPolicy(allowPerTaskModel = true, selectableModelIds = listOf("a")) else AgentSubagentModelPolicy()
        }, modelName = { "模型 A" })
        val disabled = resolver.resolve(Session("parent", "disabled")).single()
        val enabled = resolver.resolve(Session("parent", "enabled")).single()
        assertThat(modelDescription(disabled)).contains("Do not pass")
        assertThat(modelDescription(enabled)).contains("a (模型 A)")
        assertThat(SubagentModelToolDefinitions.forExecution(skill.toProtocolTool(), enabled)).isEqualTo(enabled)
    }

    @Test fun `工具能力仅拒绝明确不支持且遵循用户覆盖供应商目录优先级`() {
        val model = ModelInfo("模型", "id", "", true, remoteModelId = "unknown-test-model")
        val resolver = ModelMetadataResolver(emptyList())
        fun supports(value: ModelInfo) = SubagentModelSelection.supportsToolCalling(value.id, listOf(value), resolver)
        assertThat(SubagentModelSelection.supportsToolCalling("missing", listOf(model), resolver)).isTrue()
        assertThat(supports(model)).isTrue()
        assertThat(supports(model.copy(providerMetadata = ModelMetadataOverride(capabilities = mapOf(ModelCapability.TOOL_CALLING to SupportState.UNKNOWN))))).isTrue()
        assertThat(supports(model.copy(providerMetadata = ModelMetadataOverride(capabilities = mapOf(ModelCapability.TOOL_CALLING to SupportState.UNSUPPORTED))))).isFalse()
        assertThat(supports(model.copy(userEditedFields = setOf("capabilities")))).isFalse()
        assertThat(supports(model.copy(userEditedFields = setOf("capabilities"), capabilities = listOf("toolcalling"),
            providerMetadata = ModelMetadataOverride(capabilities = mapOf(ModelCapability.TOOL_CALLING to SupportState.UNSUPPORTED))))).isTrue()
    }

    @Test fun `目录工具能力与选择器对用户供应商和未知覆盖的判断一致`() {
        val resolver = ModelMetadataResolver(listOf(ModelMetadataRecord(
            canonicalModelId = "catalog-model", exactAliases = setOf("alias-model"), displayName = "目录模型",
            familyName = null, workload = ModelWorkload.GENERATIVE_TEXT,
            capabilities = mapOf(ModelCapability.TOOL_CALLING to SupportState.UNSUPPORTED),
            contextTokens = null, inputTokens = null, outputTokens = null, knowledgeCutoff = null, source = MetadataSource.MODELS_DEV,
        )))
        val base = ModelInfo("模型", "provider::alias-model", "", true, remoteModelId = "alias-model")
        val models = listOf(base, base.copy(capabilities = listOf("toolcalling")),
            base.copy(userEditedFields = setOf("capabilities")),
            base.copy(providerMetadata = ModelMetadataOverride(capabilities = mapOf(ModelCapability.TOOL_CALLING to SupportState.UNKNOWN))),
            base.copy(userEditedFields = setOf("capabilities"), capabilities = listOf("toolcalling")))
        assertThat(SubagentModelSelection.supportsToolCalling(base.id, listOf(base), resolver)).isFalse()
        models.forEach { model ->
            assertThat(SubagentModelSelection.supportsToolCalling(model.id, listOf(model), resolver))
                .isEqualTo(model.toModelSelectionUiModel(resolver).capabilityStates[ModelCapability.TOOL_CALLING] != SupportState.UNSUPPORTED)
        }
    }

    private fun args(vararg models: String) = buildJsonObject {
        put("background", false)
        put("tasks", JsonArray(models.map { model -> buildJsonObject {
            put("description", "任务"); put("prompt", "核对"); put("model", model)
        } }))
    }

    private fun spec(model: String? = null) = SubagentSpec("任务", "核对", null, SubagentToolProfile.INHERIT, model = model)
    private fun modelDescription(tool: ProtocolTool) = Json.parseToJsonElement(tool.function.parameters).jsonObject["properties"]!!.jsonObject["tasks"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject["model"]!!.jsonObject["description"]!!.jsonPrimitive.content

    private class Fixture(val scope: TestScope) {
        val root = Files.createTempDirectory("subagent-model-policy").toFile()
        val store = SubagentRunStore(root, UnconfinedTestDispatcher(scope.testScheduler))
        var policy = AgentSubagentModelPolicy()
        var parentModel: String? = "parent-model"
        val unsupported = mutableSetOf<String>()
        val unavailable = mutableSetOf<String>()
        val requested = mutableListOf<String>()
        val windows = mutableListOf<String>()
        val client = object : SubagentModelClient {
            override suspend fun prepare(modelId: String): PreparedSubagentModel {
                requested += modelId
                if (modelId in unavailable) throw SubagentModelUnavailable("不可用")
                return PreparedSubagentModel(modelId, this)
            }
            override suspend fun stream(modelId: String, messages: List<ProtocolMessage>, tools: List<ProtocolTool>, maxOutputTokens: Int,
                reasoningEffort: com.promenar.infinity.data.model.params.ReasoningEffort?, sessionId: String): Flow<StreamChunk> = flow {
                emit(StreamChunk.TextDelta("完成"))
                emit(StreamChunk.Usage(if (modelId == "b") ProtocolUsage(20, 10, 30) else ProtocolUsage(10, 5, 15)))
                emit(StreamChunk.Completed(CompletionReason.END_TURN))
            }
        }
        fun launcher(storage: SubagentRunStore = store, policy: suspend (Session) -> AgentSubagentModelPolicy = { this.policy }) = SubagentLauncher(
            SubagentRunner(client, NoTools, SubagentApprovalBroker(), modelSupportsToolCalling = { it !in unsupported }), storage,
            { Session(it, "agent", executionMode = "full_access") }, { parentModel },
            applicationScope = scope.backgroundScope, modelPolicyFor = policy,
            contextWindow = { windows += it; 32768 },
        )
        val launcher = launcher()
        val skill = AgentTaskSkill(policyFor = { policy }, modelSupportsToolCalling = { it !in unsupported }) { launcher }
    }

    private object NoTools : SubagentToolHost {
        override suspend fun tools(session: Session) = emptyList<ProtocolTool>()
        override suspend fun target(session: Session, tool: ProtocolTool, arguments: String): String? = null
        override suspend fun isGranted(sessionId: String, toolName: String, target: String) = false
        override suspend fun remember(sessionId: String, toolName: String, target: String) = Unit
        override fun preview(toolName: String, arguments: JsonObject) = ""
        override suspend fun execute(tool: ProtocolTool, arguments: JsonObject, context: SkillExecutionContext) = SubagentToolOutcome("", false)
    }

    private object EmptyStats : ITokenStatsRepository {
        override suspend fun getTotalUsage() = TokenUsageAggregate()
        override suspend fun getUsageBySession(sessionId: String) = getTotalUsage()
        override suspend fun getUsageByModel() = emptyList<ModelTokenStats>()
        override suspend fun getTopSessions(limit: Int) = emptyList<SessionTokenUsage>()
        override suspend fun getDailyTrend(days: Int) = emptyList<DailyTokenStats>()
        override suspend fun resetStats() = Unit
    }
}
