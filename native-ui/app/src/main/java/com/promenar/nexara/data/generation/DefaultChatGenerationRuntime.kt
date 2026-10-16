package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.document.ContextBudgetDecision
import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.domain.usecase.DEFAULT_ACTIVE_CONTEXT_WINDOW
import com.promenar.nexara.domain.usecase.inferenceDefaults
import com.promenar.nexara.domain.usecase.withDefaults
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.PhaseStatus
import com.promenar.nexara.data.model.PostProcessStatus
import com.promenar.nexara.data.model.PostProcessType
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.RagPhase
import com.promenar.nexara.data.model.RagProgress
import com.promenar.nexara.data.model.TokenUsage
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.UpdateMessageOptions
import com.promenar.nexara.data.model.findModelSpec
import com.promenar.nexara.data.rag.MemoryManager
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ProviderResolutionError
import com.promenar.nexara.data.remote.StreamConfig
import com.promenar.nexara.data.remote.middleware.StreamTextParams
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolChoice
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.ToolApprovalCreation
import com.promenar.nexara.data.repository.ApprovalCallCandidate
import com.promenar.nexara.data.repository.ToolApprovalRequestFactory
import com.promenar.nexara.data.repository.ToolExecutionLedger
import com.promenar.nexara.data.repository.ToolInvocationIdentityFactory
import com.promenar.nexara.data.repository.ToolInvocationIdentityResolution
import com.promenar.nexara.domain.generation.GenerationChunk
import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationFailureCode
import com.promenar.nexara.domain.generation.GenerationFailureCodec
import com.promenar.nexara.domain.generation.GenerationPreparationOutcome
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationSnapshot
import com.promenar.nexara.domain.generation.GenerationTerminalStatus
import com.promenar.nexara.domain.generation.DEFAULT_TOOL_ROUNDS_PER_GENERATION
import com.promenar.nexara.domain.generation.GenerationToolBudget
import com.promenar.nexara.domain.generation.GenerationToolCall
import com.promenar.nexara.domain.generation.GenerationToolUsage
import com.promenar.nexara.domain.generation.MAX_CONFIGURABLE_TOOL_ROUNDS
import com.promenar.nexara.domain.generation.GenerationToolDecision
import com.promenar.nexara.domain.repository.IAgentRepository
import com.promenar.nexara.domain.usecase.AgentConfigResolver
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.ContextBuilder
import com.promenar.nexara.ui.chat.manager.ContextBuilderParams
import com.promenar.nexara.ui.chat.manager.ContextBuilderResult
import com.promenar.nexara.ui.chat.manager.MessageManager
import com.promenar.nexara.ui.chat.manager.PostProcessor
import com.promenar.nexara.ui.chat.manager.PostProcessorParams
import com.promenar.nexara.ui.chat.manager.SessionManager
import com.promenar.nexara.ui.chat.manager.SummaryManager
import com.promenar.nexara.ui.chat.manager.ToolExecutor
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface GenerationUiPort {
    fun replaceRagPhases(phases: List<RagPhase>)
    fun updateRagPhases(transform: (List<RagPhase>) -> List<RagPhase>)
    fun setGenerating(value: Boolean)
    fun setStreamingContent(value: String)
    fun setError(failure: GenerationFailure?)
    fun setFailureSurface(surface: GenerationFailureSurface?)
    fun setProviderFailure(failure: ProviderResolution.Failure?)
    fun onHandledFailure()
    fun addPostProcessTask(type: PostProcessType, detail: String): String
    fun updatePostProcessTask(
        id: String,
        status: PostProcessStatus? = null,
        progress: Float? = null,
        detail: String? = null,
    )
    fun removePostProcessTask(id: String)
}

// 检索轨迹本身也是需要持久化的结果：未命中、嵌入不可用等情形没有引用，但检索指示器仍要显示。
private fun ContextBuilderResult.hasPersistableRagContext(): Boolean =
    ragContext.isNotBlank() || ragReferences.isNotEmpty() || citations.isNotEmpty() ||
        kgPaths.isNotEmpty() || ragUsage != null || ragTrace != null

private fun ContextBuilderResult.toMessageRagUpdateOptions(): UpdateMessageOptions? {
    if (ragReferences.isEmpty() && citations.isEmpty() && kgPaths.isEmpty() && ragTrace == null) return null
    val hasReferenceMetadata = ragReferences.isNotEmpty() || citations.isNotEmpty() || ragTrace != null
    return UpdateMessageOptions(
        ragReferences = ragReferences.ifEmpty { null },
        citations = citations.ifEmpty { null },
        kgPaths = kgPaths.ifEmpty { null },
        ragMetadata = if (hasReferenceMetadata) {
            com.promenar.nexara.data.model.RagMetadata(
                chunkCount = ragReferences.size,
                totalTokens = ragUsage?.ragSystem ?: 0,
                retrievalTimeMs = ragTrace?.searchTimeMs ?: 0,
                trace = ragTrace,
            )
        } else null,
    )
}

/**
 * 生成业务的应用级具体实现。它不持有 Activity/ViewModel，只通过 [GenerationUiPort] 发布可选 UI effects。
 */
internal class DefaultChatGenerationRuntime(
    private val settings: SharedPreferences,
    private val applicationScope: CoroutineScope,
    private val store: ChatStore,
    private val agentRepository: IAgentRepository,
    private val configResolver: AgentConfigResolver,
    private val routeGate: ChatProviderRouteGate,
    private val contextBuilder: ContextBuilder,
    private val messageManager: MessageManager,
    private val localProviderFactory: (String) -> LlmProvider,
    private val provider: LlmProvider,
    private val toolLedger: ToolExecutionLedger,
    private val toolExecutor: ToolExecutor,
    private val postProcessor: PostProcessor,
    private val memoryManager: MemoryManager?,
    private val summaryManager: SummaryManager,
    private val sessionManager: SessionManager,
    private val contentStrategy: ChatGenerationContentStrategy,
    private val ui: GenerationUiPort,
    private val skillCatalog: () -> List<com.promenar.nexara.data.skills.AgentSkillMetadata> = { emptyList() },
    private val toolGrantStore: com.promenar.nexara.data.repository.SessionToolGrantStore? = null,
    private val workspaceRepository: com.promenar.nexara.domain.repository.IWorkspaceRepository? = null,
    private val outputStore: com.promenar.nexara.data.repository.ToolOutputStore? = null,
    private val interactionStore: GenerationInteractionStore? = null,
    private val externalFolders: com.promenar.nexara.domain.storage.ExternalWorkspaceAccess? = null,
    private val sshRuntime: com.promenar.nexara.data.ssh.SshRuntime? = null,
) : ChatGenerationRuntime {
    private val inFlightCompressor = InFlightToolContextCompressor()
    private var activeToolBudget: GenerationToolBudget? = null
    private var baselineTokens = 0L
    private var mainSpentTokens = 0L
    private var summarySpentTokens = 0L
    private var summaryUsageEstimated = false
    private var preparedRoute: ChatProviderRoute? = null
    private var preparedContext: ContextBuilderResult? = null
    private var preparedPrompt: PromptRequest? = null
    private var preparedTools: List<ProtocolTool> = emptyList()
    private var toolRounds = 0
    private var finalAnswerMode = false
    private var currentAssistantMessageId: String? = null
    private var rollbackBaselineMessageIds: Set<String> = emptySet()
    private var replacementCommitted = false
    private var cachedRetrieval: com.promenar.nexara.ui.chat.manager.ContextRetrieval? = null

    override suspend fun prepare(request: GenerationRequest) {
        sshRuntime?.resumeOwner("agent:${request.sessionId}")
        val session = requireNotNull(store.getSession(request.sessionId)) { "生成会话不存在" }
        currentAssistantMessageId = request.assistantMessageId
        rollbackBaselineMessageIds = session.messages.mapTo(mutableSetOf()) { it.id }.apply {
            remove(request.assistantMessageId)
        }
        require(session.messages.any {
            it.id == assistantMessageId(request) && it.role == MessageRole.ASSISTANT
        }) { "生成目标消息不存在" }
        ui.setGenerating(true)
        activeToolBudget = readToolBudget()
        baselineTokens = consumedToolUsageInTurn(session.messages, request).tokens
        ensureAutomaticConsent(request, session.executionMode)
    }

    private suspend fun ensureAutomaticConsent(request: GenerationRequest, mode: String) {
        val consent = UnlimitedAutomationConsent(settings)
        if (consent.requiresConfirmation(toolBudget(request).maxRounds == 0, mode)) {
            val confirmed = interactionStore?.awaitDecision(request.sessionId, GenerationInteractionKind.UNLIMITED_AUTO) == true
            if (!confirmed) throw CancellationException("用户未确认不限轮次全自动执行")
            consent.confirm()
        }
    }

    override suspend fun buildContext(request: GenerationRequest): GenerationPreparationOutcome {
        val reusedRetrieval = cachedRetrieval
        if (reusedRetrieval == null) ui.replaceRagPhases(defaultRagPhases())
        val session = store.getSession(request.sessionId) ?: error("生成会话不存在")
        val agent = agentRepository.getById(session.agentId)
        val agentConfig = configResolver.resolve(agent)
        val model = session.modelId?.takeIf(String::isNotBlank)
            ?: agentConfig.modelId.takeIf(String::isNotBlank)
            ?: ProviderManager.getInstance().getMainConfiguredModelId().orEmpty()
        val effectiveRagOptions = session.ragOptions ?: RagOptions()
        val params = ContextBuilderParams(
            sessionId = request.sessionId,
            content = request.userContent,
            assistantMsgId = assistantMessageId(request),
            session = session,
            ragOptions = effectiveRagOptions,
            onRagProgress = { stage, percentage, detail ->
                messageManager.updateMessageProgress(
                    request.sessionId,
                    assistantMessageId(request),
                    RagProgress(stage, percentage, detail),
                )
                ui.updateRagPhases { updateRagProgress(it, stage, percentage, detail) }
            },
            agentSystemPrompt = agentConfig.systemPrompt.takeIf(String::isNotBlank)
                ?: com.promenar.nexara.data.agent.PresetAgentPrompts.forAgentId(session.agentId),
            sessionCustomPrompt = session.customPrompt,
            agentRetrievalConfig = agentConfig.retrievalConfig,
        )
        var routeTools: List<ProtocolTool> = emptyList()
        val preparation = try {
            routeGate.prepareWithRoute(model) { route ->
                // Vertex 的多轮函数调用要求 thought signature 原样回传；夹具闭环前保持能力关闭。
                routeTools = if (route.supportsToolCalls) contentStrategy.buildTools(session) else emptyList()
                val toolNames = routeTools.mapTo(linkedSetOf()) { it.function.name }
                contextBuilder.buildContext(
                    params.copy(
                        availableToolNames = toolNames,
                        availableSkills = if (ACTIVATE_SKILL_TOOL in toolNames) skillCatalog() else emptyList(),
                        cachedRetrieval = reusedRetrieval,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val failure = error.failureOrUnknown()
            handlePreparationFailure(request, failure, null)
            return GenerationPreparationOutcome.Handled(failure)
        }
        val (route, context) = when (preparation) {
            is ChatRoutePreparation.Failure -> {
                val providerFailure = preparation.failure
                val failure = providerFailure.toGenerationFailure()
                handlePreparationFailure(request, failure, providerFailure)
                return GenerationPreparationOutcome.Handled(failure)
            }
            is ChatRoutePreparation.Success -> preparation.route to preparation.context
        }
        ui.setProviderFailure(null)
        // 检索只依赖用户问题：首轮检索并把引用挂到首条回复上，后续工具轮次复用结果，不重复联网与检索。
        if (reusedRetrieval == null) {
            if (!context.hasPersistableRagContext()) {
                ui.replaceRagPhases(emptyList())
                messageManager.clearMessageRagState(request.sessionId, assistantMessageId(request))
            } else {
                ui.updateRagPhases { phases ->
                    val executed = phases.filter { it.status != PhaseStatus.PENDING }.map { it.id }.toSet()
                    phases.map { if (it.id in executed) it.copy(status = PhaseStatus.DONE) else it }
                }
            }
            context.toMessageRagUpdateOptions()?.let {
                messageManager.updateMessageContent(request.sessionId, assistantMessageId(request), "", it)
                messageManager.flushNonApprovalUpdatesNow(request.sessionId, assistantMessageId(request))
            }
            cachedRetrieval = context.retrieval
        }
        val tools = routeTools
        val inference = session.inferenceParams.withDefaults(agentConfig.inferenceDefaults())
        preparedRoute = route
        preparedContext = context
        preparedTools = tools
        val systemPrompt = if (finalAnswerMode) {
            context.finalSystemPrompt + FINAL_ANSWER_INSTRUCTION
        } else {
            context.finalSystemPrompt
        }
        val prompt = PromptRequest(
            messages = contentStrategy.buildProtocolMessages(
                session,
                systemPrompt,
                request.userMessageId,
                request.messageIdsToReplace,
            ),
            model = requireNotNull(route.remoteModelId),
            temperature = inference.temperature,
            topP = inference.topP,
            maxTokens = inference.maxTokens,
            frequencyPenalty = inference.frequencyPenalty,
            presencePenalty = inference.presencePenalty,
            topK = inference.topK,
            repetitionPenalty = inference.repetitionPenalty,
            tools = tools.ifEmpty { null },
            // 收尾轮仍声明工具（历史中的调用需要它们才能被部分协议解析），但禁止再发起调用。
            toolChoice = if (finalAnswerMode) ProtocolToolChoice.NONE else ProtocolToolChoice.AUTO,
            webSearch = session.options.webSearch,
            enableGeminiSearch = session.options.enableGeminiSearch,
            stream = true,
            streamTimeout = (inference.streamTimeout ?: 120).toLong() * 1000,
        )
        val offloaded = prompt.messages.map { message ->
            if (message.role != "tool" || outputStore == null) message else {
                val content = try {
                    outputStore.offload(request.sessionId, message.content)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: java.io.IOException) {
                    InFlightToolContextCompressor.headTail(message.content, 2000) +
                        "\n[Output storage unavailable; full content remains in conversation history.]"
                }
                message.copy(content = content)
            }
        }
        val window = PreparedPromptBudgetGate.resolveWindow(session, prompt, settings, route.modelId)
        val contextTokens = window.modelContextTokens
        val requestMessages = if (contextTokens != null) {
            val ratio = settings.getInt("tool_context_ratio_percent", 70).coerceIn(10, 90) / 100.0
            val target = minOf((contextTokens * ratio).toInt(), contextTokens - window.outputReserveTokens)
            val toolTokens = prompt.tools.orEmpty().sumOf {
                ApproximateTokenEstimator.estimate(it.function.name + it.function.description + it.function.parameters)
            }
            inFlightCompressor.compact(
                offloaded,
                target.coerceAtLeast(1),
                extraTokens = toolTokens,
                keepRecentRounds = settings.getInt("tool_context_keep_rounds", 3).coerceIn(1, 10),
            ) { text ->
                val limit = toolBudget(request).maxTokens
                if (finalAnswerMode || (limit > 0 && baselineTokens + mainSpentTokens + summarySpentTokens >= limit)) {
                    null
                } else {
                    var reportedSummaryUsage: TokenUsage? = null
                    val summarized = kotlinx.coroutines.withTimeoutOrNull(12_000) {
                        RoutedToolResultSummarizer(routeGate, localProviderFactory).summarizeWithUsage(
                            text, settings.getString("preset_summary_model", ""), route.modelId ?: prompt.model,
                            onUsage = { reportedSummaryUsage = it },
                        )
                    }
                    val usage = summarized?.usage ?: reportedSummaryUsage ?: TokenUsage(
                        input = ApproximateTokenEstimator.estimate(text) + 128,
                        output = 256,
                        total = ApproximateTokenEstimator.estimate(text) + 384,
                        estimated = true,
                    )
                    recordSummaryUsage(request, usage)
                    summarized?.content
                }
            }
        } else offloaded
        val budgetedPrompt = prompt.copy(messages = requestMessages)
        val hasFullContextDocuments = request.userMessageId
            ?.let { id -> session.messages.firstOrNull { it.id == id } }
            ?.userDocuments
            .isNullOrEmpty()
            .not()
        val budgetDecision = PreparedPromptBudgetGate.evaluate(
            session,
            budgetedPrompt,
            settings,
            hasFullContextDocuments,
            route.modelId,
        )
        if (budgetDecision is ContextBudgetDecision.Blocked) {
            val failure = GenerationFailure(
                code = GenerationFailureCode.CONTEXT_LIMIT,
                formatArgs = mapOf(
                    GenerationFailure.KEY_REQUIRED_TOKENS to budgetDecision.requiredTokens.toString(),
                    GenerationFailure.KEY_AVAILABLE_TOKENS to budgetDecision.availableTokens.toString(),
                ),
                technical = "full-context budget blocked: reason=${budgetDecision.reason}, " +
                    "required=${budgetDecision.requiredTokens}, available=${budgetDecision.availableTokens}",
            )
            handleDocumentBudgetFailure(request, failure)
            return GenerationPreparationOutcome.Handled(failure)
        }
        preparedPrompt = budgetedPrompt
        return GenerationPreparationOutcome.Ready
    }

    override suspend fun stream(request: GenerationRequest, attempt: Int): Flow<GenerationChunk> {
        val route = requireNotNull(preparedRoute) { "生成路由尚未准备完成" }
        val prompt = requireNotNull(preparedPrompt) { "生成请求尚未准备完成" }
        val source = if (route.useLocalProvider) {
            localProviderFactory(prompt.model).sendPrompt(prompt)
        } else {
            val client = requireNotNull(route.client) { "Router 成功但未创建云端请求客户端" }
            client.sendStream(
                StreamTextParams(
                    messages = prompt.messages,
                    model = prompt.model,
                    temperature = prompt.temperature,
                    topP = prompt.topP,
                    maxOutputTokens = prompt.maxTokens,
                    frequencyPenalty = prompt.frequencyPenalty,
                    presencePenalty = prompt.presencePenalty,
                    topK = prompt.topK,
                    repetitionPenalty = prompt.repetitionPenalty,
                    streamTimeout = prompt.streamTimeout,
                    enableGeminiSearch = prompt.enableGeminiSearch,
                    tools = preparedTools.associateBy { it.function.name },
                    toolChoice = prompt.toolChoice,
                    enableWebSearch = prompt.webSearch == true,
                ),
                StreamConfig(enableWebSearch = prompt.webSearch == true),
            )
        }
        return source.mapNotNull(::toGenerationChunk)
    }

    override fun knownToolNames(request: GenerationRequest): Set<String> =
        preparedTools.mapTo(linkedSetOf()) { it.function.name }

    override suspend fun normalize(request: GenerationRequest, snapshot: GenerationSnapshot): GenerationSnapshot {
        val calls = snapshot.toolCalls.map { ToolCall(it.id, it.name, it.arguments) }
        val normalized = contentStrategy.normalize(snapshot.content, calls)
        if (normalized.syntheticSteps.isNotEmpty()) {
            val current = store.getSession(request.sessionId)?.messages
                ?.firstOrNull { it.id == assistantMessageId(request) }
            if (current != null) {
                messageManager.updateMessageContent(
                    request.sessionId,
                    assistantMessageId(request),
                    current.content,
                    UpdateMessageOptions(
                        executionSteps = current.executionSteps.orEmpty() + normalized.syntheticSteps,
                    ),
                )
            }
        }
        return snapshot.copy(content = normalized.content)
    }

    override suspend fun finalizeStream(request: GenerationRequest, snapshot: GenerationSnapshot): GenerationSnapshot {
        val current = snapshot.toolCalls.map { ToolCall(it.id, it.name, it.arguments) }
        if (current.isNotEmpty()) return snapshot
        if (snapshot.content.isBlank()) return snapshot
        val fallback = contentStrategy.extractFallbackToolCalls(snapshot.content)
        if (fallback.isEmpty()) return snapshot
        // 文本 fallback 仅用于兼容展示清理；没有 Provider Completed IDs，绝不能转为可执行调用。
        return snapshot.copy(
            content = contentStrategy.stripToolCallMarkup(snapshot.content),
        )
    }

    override suspend fun persist(request: GenerationRequest, snapshot: GenerationSnapshot) {
        val calls = snapshot.toolCalls.map { ToolCall(it.id, it.name, it.arguments) }
        val failureEnvelope = snapshot.failure?.let(GenerationFailureCodec::encode)
        ui.setFailureSurface(
            snapshot.failure?.let { GenerationFailureSurface.INLINE_MESSAGE },
        )
        ui.setError(snapshot.failure)
        ui.setStreamingContent(snapshot.content)
        messageManager.updateMessageContent(
            request.sessionId,
            assistantMessageId(request),
            snapshot.content,
            UpdateMessageOptions(
                reasoning = snapshot.reasoning.ifBlank { null },
                tokens = mainAndSummaryUsage(request, snapshot),
                toolCalls = calls.takeIf { it.isNotEmpty() },
                clearToolCalls = calls.isEmpty(),
                citations = snapshot.citations.map {
                    com.promenar.nexara.data.model.Citation(it.title, it.url, it.source)
                }.takeIf { it.isNotEmpty() },
                isError = snapshot.failure?.let { true },
                errorMessage = failureEnvelope,
                clearError = snapshot.failure == null,
            ),
        )
    }

    override suspend fun recordTaskUsage(request: GenerationRequest, snapshot: GenerationSnapshot) {
        mainSpentTokens += snapshot.totalTokens
        val content = store.getSession(request.sessionId)?.messages?.firstOrNull { it.id == assistantMessageId(request) }?.content
            ?: return
        messageManager.updateMessageContent(request.sessionId, assistantMessageId(request), content,
            UpdateMessageOptions(tokens = mainAndSummaryUsage(request, snapshot)))
    }

    private fun mainAndSummaryUsage(request: GenerationRequest, snapshot: GenerationSnapshot): TokenUsage {
        val existing = store.getSession(request.sessionId)?.messages?.firstOrNull { it.id == assistantMessageId(request) }?.tokens
            ?: TokenUsage()
        fun add(a: Int, b: Int) = (a.toLong() + b).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return existing.copy(
            input = add(snapshot.inputTokens, existing.summaryInput),
            output = add(snapshot.outputTokens, existing.summaryOutput),
            total = add(snapshot.totalTokens, existing.summaryTotal),
            estimated = snapshot.tokensEstimated || existing.summaryEstimated,
        )
    }

    private suspend fun recordSummaryUsage(request: GenerationRequest, usage: TokenUsage) {
        summarySpentTokens += usage.total
        summaryUsageEstimated = summaryUsageEstimated || usage.estimated
        val message = store.getSession(request.sessionId)?.messages?.firstOrNull { it.id == assistantMessageId(request) } ?: return
        val existing = message.tokens ?: TokenUsage()
        fun add(a: Int, b: Int) = (a.toLong() + b).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val combined = existing.copy(
            input = add(existing.input, usage.input), output = add(existing.output, usage.output), total = add(existing.total, usage.total),
            estimated = existing.estimated || usage.estimated,
            summaryInput = add(existing.summaryInput, usage.input), summaryOutput = add(existing.summaryOutput, usage.output),
            summaryTotal = add(existing.summaryTotal, usage.total), summaryEstimated = existing.summaryEstimated || usage.estimated,
        )
        messageManager.updateMessageContent(request.sessionId, assistantMessageId(request), message.content, UpdateMessageOptions(tokens = combined))
        messageManager.flushNonApprovalUpdatesNow(request.sessionId, assistantMessageId(request))
    }

    override fun additionalTokenUsage(request: GenerationRequest): GenerationToolUsage =
        GenerationToolUsage(tokens = summarySpentTokens, tokensEstimated = summaryUsageEstimated)

    override suspend fun handleTools(
        request: GenerationRequest,
        toolCalls: List<GenerationToolCall>,
    ): GenerationToolDecision {
        val session = store.getSession(request.sessionId) ?: error("生成会话不存在")
        messageManager.flushGenerationTerminal(request.sessionId, assistantMessageId(request))
        ensureAutomaticConsent(request, session.executionMode.ifEmpty { "semi" })
        val calls = toolCalls.map { ToolCall(it.id, it.name, it.arguments) }
        val policyPendingIds = contentStrategy
            .pendingApprovalIds(calls, session.executionMode.ifEmpty { "semi" })
            .toSet()
        val preparedByName = preparedTools.groupBy { it.function.name }
        val targets = calls.associate { call ->
            val tool = preparedByName[call.name]?.singleOrNull()
            call.id to tool?.let { resolveSessionToolTarget(session, it, call.arguments, workspaceRepository, externalFolders, sshRuntime?.let { runtime -> runtime::sessionHost }) }
        }
        val requestedPendingIds = policyPendingIds.filterTo(mutableSetOf()) { id ->
            val call = calls.first { it.id == id }
            val target = targets[id]
            target == null || toolGrantStore?.contains(session.id, call.name, target) != true
        }
        val grantTargets = (policyPendingIds - requestedPendingIds).associateWith { targets.getValue(it)!! }
        val validInvocations = calls.mapNotNull { call ->
            val tool = preparedByName[call.name]?.singleOrNull() ?: return@mapNotNull null
            val resolved = ToolInvocationIdentityFactory.fromPreparedToolCall(
                call = call,
                tool = tool,
                requiresApproval = call.id in requestedPendingIds,
            ) as? ToolInvocationIdentityResolution.Valid ?: return@mapNotNull null
            Triple(call, tool, resolved.identity)
        }
        val pendingIds = validInvocations
            .filter { (call, _, identity) -> call.id in requestedPendingIds && identity.requiresApproval }
            .map { it.first.id }
        val allowedIds = validInvocations
            .filterNot { it.first.id in pendingIds }
            .mapTo(linkedSetOf()) { it.first.id }
        if (pendingIds.isEmpty()) toolRounds++
        toolExecutor.executeTools(
            request.sessionId,
            assistantMessageId(request),
            calls,
            allowedIds,
            preparedTools,
            expectedGrantTargets = grantTargets,
        )
        if (pendingIds.isNotEmpty()) {
            val approval = ToolApprovalRequestFactory.create(
                assistantMessageId = assistantMessageId(request),
                candidates = validInvocations.filter { it.first.id in pendingIds }.map { (call, tool, identity) ->
                    ApprovalCallCandidate(call, tool.risk, identity, executionTarget = targets[call.id])
                },
                reason = "Execution mode: ${session.executionMode.ifEmpty { "semi" }}",
            )
            messageManager.flushNonApprovalUpdatesNow(request.sessionId, assistantMessageId(request))
            val creation = toolLedger.createToolApproval(
                request.sessionId,
                assistantMessageId(request),
                calls,
                pendingIds.toSet(),
                approval,
            )
            if (creation == ToolApprovalCreation.CONFLICT) {
                ui.setFailureSurface(GenerationFailureSurface.OVERLAY)
                ui.setError(
                    GenerationFailure.busy(
                        technical = "tool approval conflict: sessionId=${request.sessionId}",
                    ),
                )
                ui.setGenerating(false)
                return GenerationToolDecision.WAIT_FOR_APPROVAL
            }
            messageManager.mirrorPersistedPendingApprovalState(
                request.sessionId,
                assistantMessageId(request),
                pendingIds,
            )
            store.updateSession(request.sessionId) {
                it.copy(
                    approvalRequest = approval,
                    loopStatus = com.promenar.nexara.data.model.LoopStatus.WAITING_FOR_APPROVAL,
                )
            }
            // 新回复已持久化待审批调用，本次运行在此结束且不会再到达终态，旧回复必须在此完成替换。
            commitReplacement(request)
            ui.setGenerating(false)
            return GenerationToolDecision.WAIT_FOR_APPROVAL
        }
        return GenerationToolDecision.CONTINUE
    }

    override fun toolBudget(request: GenerationRequest): GenerationToolBudget = activeToolBudget ?: readToolBudget()

    private fun readToolBudget(): GenerationToolBudget {
        val saved = settings.getInt(LOOP_LIMIT_KEY, DEFAULT_TOOL_ROUNDS_PER_GENERATION)
        return GenerationToolBudget(
            maxRounds = if (saved == 0) 0 else saved.coerceIn(1, MAX_CONFIGURABLE_TOOL_ROUNDS),
            maxCalls = if (saved == 0) 0 else com.promenar.nexara.domain.generation.MAX_TOOL_CALLS_PER_GENERATION,
            maxTokens = settings.getInt("task_token_limit", 0).coerceAtLeast(0).toLong(),
        )
    }

    override fun estimatedInputTokens(request: GenerationRequest): Int = preparedPrompt?.let { prompt ->
        prompt.messages.sumOf(ApproximateTokenEstimator::estimate) + prompt.tools.orEmpty().sumOf {
            ApproximateTokenEstimator.estimate(it.function.name + it.function.description + it.function.parameters)
        }
    } ?: 0

    override fun loopDetectionThreshold(request: GenerationRequest): Int =
        settings.getInt("tool_loop_repeat_limit", 3).coerceIn(2, 20)

    override suspend fun confirmLoopContinuation(request: GenerationRequest): Boolean =
        interactionStore?.awaitDecision(request.sessionId, GenerationInteractionKind.REPEATED_TOOL) == true

    override suspend fun consumedToolUsage(request: GenerationRequest): GenerationToolUsage {
        val messages = store.getSession(request.sessionId)?.messages ?: return GenerationToolUsage()
        return consumedToolUsageInTurn(messages, request)
    }

    override suspend fun rejectToolCalls(
        request: GenerationRequest,
        toolCalls: List<GenerationToolCall>,
        reason: String,
    ) {
        messageManager.flushGenerationTerminal(request.sessionId, assistantMessageId(request))
        toolExecutor.rejectTools(
            request.sessionId,
            assistantMessageId(request),
            toolCalls.map { ToolCall(it.id, it.name, it.arguments) },
            reason,
        )
    }

    override fun enterFinalAnswerMode(request: GenerationRequest) {
        finalAnswerMode = true
    }

    override suspend fun prepareContinuation(request: GenerationRequest): String {
        val session = store.getSession(request.sessionId) ?: error("生成会话不存在")
        val nextAssistantMessageId = com.promenar.nexara.domain.usecase.IdGenerator.message("ai")
        messageManager.addMessage(
            request.sessionId,
            com.promenar.nexara.data.model.Message(
                id = nextAssistantMessageId,
                role = MessageRole.ASSISTANT,
                content = "",
                modelId = session.modelId,
                parentMessageId = request.userMessageId,
                createdAt = System.currentTimeMillis(),
            ),
        )
        currentAssistantMessageId = nextAssistantMessageId
        return nextAssistantMessageId
    }

    override suspend fun postProcess(request: GenerationRequest, snapshot: GenerationSnapshot) {
        ui.setStreamingContent("")
        val userMessageId = request.userMessageId ?: return
        val context = requireNotNull(preparedContext)
        val session = store.getSession(request.sessionId) ?: return
        try {
            val rag = session.ragOptions
            postProcessor.updateStats(
                PostProcessorParams(
                    sessionId = request.sessionId,
                    assistantMsgId = assistantMessageId(request),
                    userMsgId = userMessageId,
                    userContent = request.userContent,
                    assistantContent = snapshot.content,
                    agent = com.promenar.nexara.data.model.Agent(session.agentId, session.agentId),
                    session = session,
                    ragEnabled = rag?.enableMemory == true || rag?.enableDocs == true,
                    ragUsage = context.ragUsage,
                    accumulatedUsage = TokenUsage(snapshot.inputTokens, snapshot.outputTokens, snapshot.totalTokens),
                    totalContextTokens = context.ragUsage?.ragSystem ?: 0,
                    modelId = session.modelId ?: "",
                ),
            )
            val memory = memoryManager
            if (rag?.enableMemory == true && request.userContent.isNotBlank() &&
                snapshot.content.isNotBlank() && memory != null
            ) {
                try {
                    memory.addTurnToMemory(
                        request.sessionId,
                        request.userContent,
                        snapshot.content,
                        userMessageId,
                        assistantMessageId(request),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    NexaraLogger.logError("[GenerationRuntime] addTurnToMemory failed", error)
                }
            }
            val windowSize = session.inferenceParams?.activeContextWindow ?: DEFAULT_ACTIVE_CONTEXT_WINDOW
            if (session.messages.size <= windowSize) return
            val activeIds = contentStrategy.safeActiveWindow(session.messages, windowSize).map { it.id }.toSet()
            val overflow = session.messages.filter { it.id !in activeIds && !it.isArchived }
            if (overflow.isEmpty()) return
            val threshold = session.inferenceParams?.autoSummaryThreshold ?: 0.8
            val maxTokens = findModelSpec(session.modelId.orEmpty())?.contextLength ?: 128000
            val nearContextLimit = snapshot.totalTokens > maxTokens * threshold
            // 溢出消息离开活动窗口后不再发送给模型，必须进入摘要；按批处理以摊薄摘要调用。
            if (!nearContextLimit && overflow.size < overflowBatchSize(windowSize)) return
            if (rag?.enableMemory == true) archiveMessages(request, session.modelId.orEmpty(), overflow)
            val summarized = summarize(request, session, overflow)
            if (!summarized) return
            overflow.forEach { message ->
                messageManager.updateMessage(request.sessionId, message.id, message.copy(isArchived = true))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            NexaraLogger.logError("[GenerationRuntime] postProcess failed", error)
        }
    }

    override suspend fun markTerminal(
        request: GenerationRequest,
        status: GenerationTerminalStatus,
        snapshot: GenerationSnapshot,
        cause: Throwable?,
    ) {
        messageManager.markGenerationTerminal(
            request.sessionId,
            assistantMessageId(request),
            snapshot.content,
            when (status) {
                GenerationTerminalStatus.SUCCESS -> "success"
                GenerationTerminalStatus.ERROR -> "error"
                GenerationTerminalStatus.CANCELLED -> "cancelled"
            },
            if (status == GenerationTerminalStatus.ERROR) {
                GenerationFailureCodec.encode(
                    snapshot.failure
                        ?: cause?.failureOrUnknown()
                        ?: GenerationFailure.unknown("terminal error without failure"),
                )
            } else null,
        )
        if (request.messageIdsToReplace.isNotEmpty()) {
            if (status == GenerationTerminalStatus.SUCCESS) {
                commitReplacement(request)
            } else if (!replacementCommitted) {
                messageManager.discardPreparedMessages(
                    request.sessionId,
                    messagesCreatedSincePrepare(request),
                )
            }
        }
    }

    override suspend fun flush(request: GenerationRequest) =
        messageManager.flushGenerationTerminal(request.sessionId, assistantMessageId(request))

    override suspend fun releaseToolResources(request: GenerationRequest, awaitingApproval: Boolean) {
        val owner = "agent:${request.sessionId}"
        if (awaitingApproval) sshRuntime?.pauseOwner(owner) else sshRuntime?.closeOwner(owner)
    }

    override fun cancelProvider() = provider.cancel()

    /** 删除被本次生成替换的旧回复；只执行一次，删除失败向上抛出以保留旧回复并走持久化失败路径。 */
    private suspend fun commitReplacement(request: GenerationRequest) {
        if (replacementCommitted || request.messageIdsToReplace.isEmpty()) return
        val present = store.getSession(request.sessionId)?.messages.orEmpty().mapTo(hashSetOf()) { it.id }
        request.messageIdsToReplace.filter { it in present }.forEach { replacedId ->
            if (store.getSession(request.sessionId)?.messages?.any { it.id == replacedId } == true) {
                messageManager.deleteMessage(request.sessionId, replacedId)
            }
        }
        replacementCommitted = true
    }

    private fun assistantMessageId(request: GenerationRequest): String =
        currentAssistantMessageId ?: request.assistantMessageId

    private fun messagesCreatedSincePrepare(request: GenerationRequest): List<String> {
        val currentIds = store.getSession(request.sessionId)?.messages.orEmpty()
            .map { it.id }
            .filterNot { it in rollbackBaselineMessageIds }
        return (listOf(request.assistantMessageId) + currentIds + assistantMessageId(request)).distinct()
    }

    private suspend fun handlePreparationFailure(
        request: GenerationRequest,
        generationFailure: GenerationFailure,
        failure: ProviderResolution.Failure?,
    ) {
        ui.setFailureSurface(
            if (request.messageIdsToReplace.isEmpty()) {
                GenerationFailureSurface.INLINE_MESSAGE
            } else {
                GenerationFailureSurface.OVERLAY
            },
        )
        ui.setError(generationFailure)
        ui.setProviderFailure(failure)
        ui.setGenerating(false)
        ui.onHandledFailure()
        if (request.messageIdsToReplace.isNotEmpty()) {
            messageManager.discardPreparedMessages(
                request.sessionId,
                messagesCreatedSincePrepare(request),
            )
        } else {
            messageManager.markGenerationTerminal(
                request.sessionId,
                assistantMessageId(request),
                "",
                "error",
                GenerationFailureCodec.encode(generationFailure),
            )
        }
    }

    private suspend fun handleDocumentBudgetFailure(
        request: GenerationRequest,
        generationFailure: GenerationFailure,
    ) {
        ui.setFailureSurface(GenerationFailureSurface.OVERLAY)
        ui.setError(generationFailure)
        ui.setProviderFailure(null)
        ui.setGenerating(false)
        ui.onHandledFailure()
        if (request.messageIdsToReplace.isNotEmpty()) {
            messageManager.discardPreparedMessages(
                request.sessionId,
                messagesCreatedSincePrepare(request),
            )
        } else if (toolRounds == 0) {
            val preparedIds = listOfNotNull(
                assistantMessageId(request),
                request.userMessageId.takeIf { request.rollbackUserOnPreparationFailure },
            )
            messageManager.discardPreparedMessages(request.sessionId, preparedIds)
        }
    }

    private suspend fun archiveMessages(
        request: GenerationRequest,
        modelId: String,
        messages: List<com.promenar.nexara.data.model.Message>,
    ) {
        val taskId = ui.addPostProcessTask(PostProcessType.ARCHIVE_TO_RAG, "Archiving ${messages.size} messages")
        try {
            postProcessor.archiveMessagesToRag(request.sessionId, messages, modelId) { progress, detail ->
                ui.updatePostProcessTask(taskId, progress = progress, detail = detail)
            }
            ui.updatePostProcessTask(taskId, PostProcessStatus.DONE, 1f)
            applicationScope.launch {
                kotlinx.coroutines.delay(3000)
                ui.removePostProcessTask(taskId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ui.updatePostProcessTask(taskId, PostProcessStatus.ERROR, detail = null)
        }
    }

    private suspend fun summarize(
        request: GenerationRequest,
        session: com.promenar.nexara.data.model.Session,
        messages: List<com.promenar.nexara.data.model.Message>,
    ): Boolean {
        val taskId = ui.addPostProcessTask(PostProcessType.AUTO_SUMMARY, "Summarizing conversation")
        return try {
            val summary = summaryManager.summarizeOrNull(
                session.summary,
                messages,
                settings.getString("preset_summary_model", ""),
                session.modelId.orEmpty(),
            ) { ui.updatePostProcessTask(taskId, detail = it) }
            if (summary.isNullOrBlank()) {
                ui.updatePostProcessTask(taskId, PostProcessStatus.ERROR, detail = null)
                return false
            }
            if (summary != session.summary) sessionManager.updateSession(request.sessionId, mapOf("summary" to summary))
            ui.updatePostProcessTask(taskId, PostProcessStatus.DONE, 1f)
            applicationScope.launch {
                kotlinx.coroutines.delay(3000)
                ui.removePostProcessTask(taskId)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ui.updatePostProcessTask(taskId, PostProcessStatus.ERROR, detail = null)
            false
        }
    }

    private fun overflowBatchSize(windowSize: Int): Int = maxOf(MIN_OVERFLOW_BATCH, windowSize / 2)

    private fun toGenerationChunk(chunk: StreamChunk): GenerationChunk? = when (chunk) {
        is StreamChunk.TextDelta -> GenerationChunk.Text(chunk.content, chunk.reasoning)
        is StreamChunk.Thinking -> GenerationChunk.Thinking(chunk.content)
        is StreamChunk.ToolCallDelta -> GenerationChunk.ToolCall(chunk.id, chunk.name, chunk.arguments)
        is StreamChunk.Usage -> GenerationChunk.Usage(chunk.usage.input, chunk.usage.output, chunk.usage.total)
        is StreamChunk.Citations -> GenerationChunk.Citations(
            chunk.citations.map { com.promenar.nexara.domain.generation.GenerationCitation(it.title, it.url, it.source) },
        )
        is StreamChunk.Error -> GenerationChunk.Failure(
            GenerationFailure(
                code = chunk.code,
                formatArgs = chunk.retryAfterSeconds
                    ?.takeIf { it > 0 }
                    ?.let { mapOf(GenerationFailure.KEY_RETRY_AFTER_SECONDS to it.toString()) }
                    .orEmpty(),
                technical = chunk.technical,
                cause = chunk.cause,
            ),
        )
        is StreamChunk.Completed -> GenerationChunk.Completed(chunk.reason, chunk.completedToolCallIds)
        is StreamChunk.ToolCallLifecycle -> null
    }

    private fun defaultRagPhases() = listOf(
        RagPhase("embed", "Embedding query", PhaseStatus.PENDING),
        RagPhase("memory", "Searching memory", PhaseStatus.PENDING),
        RagPhase("docs", "Searching documents", PhaseStatus.PENDING),
        RagPhase("hybrid", "Hybrid fusion", PhaseStatus.PENDING),
        RagPhase("rank", "Ranking results", PhaseStatus.PENDING),
        RagPhase("rerank", "Reranking", PhaseStatus.PENDING),
        RagPhase("kg", "KG retrieval", PhaseStatus.PENDING),
        RagPhase("ready", "Context ready", PhaseStatus.PENDING),
    )

    private fun updateRagProgress(
        phases: List<RagPhase>,
        stage: String,
        percentage: Int,
        detail: String?,
    ): List<RagPhase> {
        val phaseId = when {
            stage.contains("Embedding", true) -> "embed"
            stage.contains("memory", true) -> "memory"
            stage.contains("document", true) -> "docs"
            stage.contains("Hybrid", true) || stage.contains("fusion", true) -> "hybrid"
            stage.contains("Rerank", true) -> "rerank"
            stage.contains("Ranking", true) || stage.contains("Rank", true) -> "rank"
            stage.contains("KG", true) -> "kg"
            stage.contains("Context ready", true) -> "ready"
            else -> return phases
        }
        val target = phases.indexOfFirst { it.id == phaseId }
        if (target < 0) return phases
        return phases.mapIndexed { index, phase ->
            when {
                index == target -> phase.copy(
                    status = if (phaseId == "ready") PhaseStatus.DONE else PhaseStatus.ACTIVE,
                    progress = if (phaseId == "ready") 100 else percentage,
                    detail = detail,
                )
                index < target && phase.status == PhaseStatus.ACTIVE -> phase.copy(status = PhaseStatus.DONE)
                else -> phase
            }
        }
    }

    private fun ProviderResolution.Failure.toGenerationFailure(): GenerationFailure {
        val code = when (reason) {
            ProviderResolutionError.API_KEY_MISSING,
            ProviderResolutionError.VERTEX_CREDENTIAL_INVALID,
            -> GenerationFailureCode.AUTH

            ProviderResolutionError.MODEL_NOT_FOUND,
            ProviderResolutionError.MODEL_DISABLED,
            ProviderResolutionError.PROVIDER_ID_MISSING,
            ProviderResolutionError.PROVIDER_NOT_FOUND,
            ProviderResolutionError.PROVIDER_DISABLED,
            ProviderResolutionError.BASE_URL_INVALID,
            ProviderResolutionError.MODEL_PROVIDER_MISMATCH,
            ProviderResolutionError.PROTOCOL_MISMATCH,
            ProviderResolutionError.PROTOCOL_UNSUPPORTED,
            ProviderResolutionError.LOCAL_INFERENCE_UNAVAILABLE,
            -> GenerationFailureCode.INVALID_REQUEST
        }
        return GenerationFailure(
            code = code,
            technical = buildString {
                append("provider route failure: reason=")
                append(reason.name)
                append(", modelId=")
                append(modelId)
                providerId?.let {
                    append(", providerId=")
                    append(it)
                }
            },
        )
    }

    private companion object {
        const val LOOP_LIMIT_KEY = "loop_limit"
        const val ACTIVATE_SKILL_TOOL = "activate_skill"
        const val MIN_OVERFLOW_BATCH = 4
        const val FINAL_ANSWER_INSTRUCTION = "\n\n## Tool Budget Exhausted\n" +
            "The tool budget for this request is used up. Do NOT call any tool. " +
            "Using only the results already obtained, summarize what was completed, the current state, " +
            "and the remaining steps, and tell the user they can send a message to continue.\n"
    }

}

/**
 * 统计 [request] 所属对话轮在本次生成之前已发起的工具轮次与调用数。
 * 只计本轮用户消息之后、下一条用户消息之前的助手消息；本次生成的目标消息与待替换的旧回复不计入。
 */
internal fun consumedToolUsageInTurn(
    messages: List<com.promenar.nexara.data.model.Message>,
    request: GenerationRequest,
): GenerationToolUsage {
    val userMessageId = request.userMessageId ?: return GenerationToolUsage()
    val start = messages.indexOfFirst { it.id == userMessageId && it.role == MessageRole.USER }
    if (start < 0) return GenerationToolUsage()
    val excluded = request.messageIdsToReplace + request.assistantMessageId
    var rounds = 0
    var calls = 0
    var tokens = 0L
    var estimated = false
    for (message in messages.drop(start + 1)) {
        if (message.role == MessageRole.USER) break
        if (message.role != MessageRole.ASSISTANT || message.id in excluded) continue
        val usage = message.tokens
        val reported = usage?.let { maxOf(it.total.toLong(), it.input.toLong() + it.output) } ?: 0
        if (reported > 0) {
            tokens += reported
            estimated = estimated || usage?.estimated == true
        } else {
            tokens += ApproximateTokenEstimator.estimate(message.content + message.reasoning.orEmpty()) +
                message.toolCalls.orEmpty().sumOf { ApproximateTokenEstimator.estimate(it.name + it.arguments) }
            if (message.content.isNotBlank() || !message.reasoning.isNullOrBlank() || !message.toolCalls.isNullOrEmpty()) estimated = true
        }
        val count = message.toolCalls?.size ?: 0
        if (count > 0) {
            rounds++
            calls += count
        }
    }
    return GenerationToolUsage(rounds, calls, tokens, estimated)
}
