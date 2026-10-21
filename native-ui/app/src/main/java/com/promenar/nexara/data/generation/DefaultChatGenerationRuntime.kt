package com.promenar.nexara.data.generation

import com.promenar.nexara.data.model.TurnMemoryState
import android.content.SharedPreferences
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.document.ContextBudgetDecision
import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.domain.usecase.resolvedKeepRecentTurns
import com.promenar.nexara.domain.usecase.inferenceDefaults
import com.promenar.nexara.domain.usecase.withDefaults
import com.promenar.nexara.domain.usecase.resolvedReasoningEffort
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
import com.promenar.nexara.data.rag.MemoryManager
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ProviderResolutionError
import com.promenar.nexara.data.remote.StreamConfig
import com.promenar.nexara.data.remote.middleware.StreamTextParams
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.ReasoningReplayCodec
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ProtocolToolChoice
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.ToolApprovalCreation
import com.promenar.nexara.data.repository.RolledBackSummaryUsageRecord
import com.promenar.nexara.data.repository.RolledBackSummaryUsageStore
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
import com.promenar.nexara.domain.generation.DEFAULT_STREAM_RETRY_LIMIT
import com.promenar.nexara.domain.generation.MAX_STREAM_RETRY_LIMIT
import com.promenar.nexara.domain.generation.STREAM_RETRY_LIMIT_KEY
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
    private val skillCatalog: com.promenar.nexara.data.skills.SkillCatalog? = null,
    private val toolAllowlistStore: com.promenar.nexara.data.repository.ToolAllowlistStore? = null,
    private val workspaceRepository: com.promenar.nexara.domain.repository.IWorkspaceRepository? = null,
    private val outputStore: com.promenar.nexara.data.repository.ToolOutputStore? = null,
    private val interactionStore: GenerationInteractionStore? = null,
    private val externalFolders: com.promenar.nexara.domain.storage.ExternalWorkspaceAccess? = null,
    private val sshRuntime: com.promenar.nexara.data.ssh.SshRuntime? = null,
    private val compactionPipeline: ContextCompactionPipeline = ContextCompactionPipeline(),
    private val compactionService: ContextCompactionService? = null,
    /** 会话当前工作区，用于系统提示词的工作区说明；null 时按私有工作区说明。 */
    private val sessionWorkspace: (suspend (String) -> com.promenar.nexara.data.workspace.SessionWorkspace)? = null,
    /** 构建工具表的调度器；工具注册表会同步扫描技能目录，不能落在主线程。 */
    private val mediaContextLoader: ViewedMediaContextLoader? = null,
    /** 会话图谱抽取排队；为空或全局开关关闭时不抽取。 */
    private val sessionGraph: com.promenar.nexara.data.rag.SessionGraphScheduler? = null,
    private val subagentUsage: com.promenar.nexara.data.subagent.SubagentUsageLedger? = null,
    private val toolBuildDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) : ChatGenerationRuntime {
    private val compactionTurnState = ContextCompactionTurnState()
    private var activeToolBudget: GenerationToolBudget? = null
    private var baselineTokens = 0L
    private var mainSpentTokens = 0L
    private var summarySpentTokens = 0L
    private var summaryUsageEstimated = false
    private var preparedRoute: ChatProviderRoute? = null
    private var preparedContext: ContextBuilderResult? = null
    private var preparedContextSummary: String? = null
    private var preparedPrompt: PromptRequest? = null
    private var preparedTools: List<ProtocolTool> = emptyList()
    private var toolRounds = 0
    private var finalAnswerMode = false
    private var currentAssistantMessageId: String? = null
    private var rollbackBaselineMessageIds: Set<String> = emptySet()
    private var replacementCommitted = false
    private var manualCompaction = false
    private var cachedRetrieval: com.promenar.nexara.ui.chat.manager.ContextRetrieval? = null

    /** 生成运行在主线程作用域；工具表要按会话扫描技能目录，统一放到 IO 上构建。 */
    private suspend fun buildToolsOffMain(session: com.promenar.nexara.data.model.Session, modelId: String? = preparedRoute?.modelId): List<ProtocolTool> =
        kotlinx.coroutines.withContext(toolBuildDispatcher) { contentStrategy.buildTools(if (modelId == null) session else session.copy(modelId = modelId)) }

    private suspend fun buildMessagesWithMedia(
        session: com.promenar.nexara.data.model.Session,
        systemPrompt: String,
        pinnedUserMessageId: String? = null,
        excludedMessageIds: Set<String> = emptySet(),
        modelId: String? = null,
    ): List<ProtocolMessage> {
        val messages = contentStrategy.buildProtocolMessages(session, systemPrompt, pinnedUserMessageId, excludedMessageIds, modelId)
        return mediaContextLoader?.attach(session, messages, modelId) ?: messages
    }

    private suspend fun scanSkills(workspace: com.promenar.nexara.data.workspace.SessionWorkspace?): List<com.promenar.nexara.data.skills.DiscoveredSkill> {
        val directory = workspace as? com.promenar.nexara.data.workspace.SessionWorkspace.Directory
        return try {
            skillCatalog?.scan(directory?.let { com.promenar.nexara.data.skills.SkillWorkspace(it.workspace.id, it.root) })?.skills.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            NexaraLogger.log("[AgentSkills] 上下文目录扫描失败：${failure.javaClass.simpleName}")
            emptyList()
        }
    }

    private suspend fun compactContext(
        request: GenerationRequest,
        session: com.promenar.nexara.data.model.Session,
        prompt: PromptRequest,
        systemPrompt: String,
        manual: Boolean = false,
        currentTurnId: String? = request.userMessageId,
        onProgress: (ContextCompactionStage) -> Unit = {},
    ): ContextCompactionResult {
        val operation: suspend () -> ContextCompactionResult = {
            val eligible = session.copy(messages = session.messages.filterNot { it.id in request.messageIdsToReplace })
            compactionPipeline.compact(
                ContextCompactionInput(
                    session = eligible,
                    currentTurnId = currentTurnId,
                    keepRecentTurns = session.inferenceParams.resolvedKeepRecentTurns,
                    threshold = session.inferenceParams?.autoSummaryThreshold ?: 0.8,
                    window = PreparedPromptBudgetGate.resolveWindow(session, prompt, settings, preparedRoute?.modelId),
                    manual = manual,
                    turnState = compactionTurnState,
                ),
                buildPrompt = { updated ->
                    val updatedSystem = replaceHistorySummary(systemPrompt, updated.summary, preparedContextSummary)
                    prompt.copy(messages = offloadToolResults(
                        request.sessionId,
                        buildMessagesWithMedia(
                            updated, updatedSystem, currentTurnId, request.messageIdsToReplace, preparedRoute?.modelId,
                        ),
                    ))
                },
                summarizeTool = { text -> summarizeToolResult(request, prompt, text) },
                summarizeHistory = { previous, messages ->
                    val estimatedInput = 128L + ApproximateTokenEstimator.estimate(previous.summary.orEmpty()) +
                        messages.sumOf { ApproximateTokenEstimator.estimate(it.content.take(2000)).toLong() + 8 }
                    if (!canSpendOnSummary(request, estimatedInput + 1024)) null else {
                        if (memoryEnabled(session)) {
                            archiveMessages(request, preparedRoute?.modelId ?: prompt.model, messages)
                        }
                        if (!summarize(request, previous, messages)) null else {
                            store.getSession(request.sessionId)?.let { updated ->
                                updated.copy(messages = updated.messages.filterNot { it.id in request.messageIdsToReplace })
                            }
                        }
                    }
                },
                onProgress = onProgress,
            )
        }
        return if (manual || compactionService == null) operation()
            else compactionService.compactAutomatically(request.sessionId, operation)
    }

    private fun canSpendOnSummary(request: GenerationRequest, estimatedTokens: Long): Boolean {
        val limit = toolBudget(request).maxTokens
        return !finalAnswerMode && (limit <= 0 ||
            baselineTokens + mainSpentTokens + summarySpentTokens + estimatedTokens <= limit)
    }

    private suspend fun summarizeToolResult(request: GenerationRequest, prompt: PromptRequest, text: String): String? {
        if (!canSpendOnSummary(request, ApproximateTokenEstimator.estimate(text).toLong() + 384)) return null
        var reported: TokenUsage? = null
        var result: RoutedToolSummary? = null
        try {
            result = kotlinx.coroutines.withTimeoutOrNull(12_000) {
                RoutedToolResultSummarizer(routeGate, localProviderFactory).summarizeWithUsage(
                    text, preparedRoute?.modelId ?: prompt.model, onUsage = { reported = it },
                )
            }
            return result?.content
        } finally {
            val usage = result?.usage ?: reported ?: TokenUsage(
                input = ApproximateTokenEstimator.estimate(text) + 128,
                output = 256,
                total = ApproximateTokenEstimator.estimate(text) + 384,
                estimated = true,
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { recordSummaryUsage(request, usage) }
        }
    }

    private suspend fun offloadToolResults(
        sessionId: String,
        messages: List<com.promenar.nexara.data.remote.protocol.ProtocolMessage>,
    ) = messages.map { message ->
        if (message.role != "tool" || message.name == "view_media" || outputStore == null) message else {
            val content = try {
                outputStore.offload(sessionId, message.content)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: java.io.IOException) {
                ContextCompactionPipeline.headTail(message.content, 2000)
            }
            message.copy(content = content)
        }
    }

    /** 手动压缩只重建系统与现有历史，不触发新的联网或知识库检索。 */
    private suspend fun prepareStoredContext(sessionId: String): StoredContext {
        val session = requireNotNull(store.getSession(sessionId)) { "压缩会话不存在" }
        val agent = agentRepository.getById(session.agentId)
        val config = configResolver.resolve(agent)
        val model = session.modelId?.takeIf(String::isNotBlank)
            ?: config.modelId.takeIf(String::isNotBlank)
            ?: ProviderManager.getInstance().getMainConfiguredModelId().orEmpty()
        val lastUser = session.messages.lastOrNull { it.role == MessageRole.USER }
        val request = GenerationRequest(
            sessionId = sessionId,
            assistantMessageId = "manual-compaction-${java.util.UUID.randomUUID()}",
            userMessageId = lastUser?.id,
            userContent = lastUser?.content.orEmpty(),
            imageDataUrls = emptyList(),
            runtimePolicy = com.promenar.nexara.domain.generation.GenerationRuntimePolicy.FOREGROUND_ONLY,
        )
        currentAssistantMessageId = request.assistantMessageId
        activeToolBudget = readToolBudget()
        preparedRoute = when (val result = routeGate.prepare(model) { Unit }) {
            is ChatRoutePreparation.Success -> result.route
            is ChatRoutePreparation.Failure -> ChatProviderRoute(
                null, model, result.failure, modelId = model, supportsToolCalls = false,
            )
        }
        val tools = if (preparedRoute?.supportsToolCalls == true) buildToolsOffMain(session) else emptyList()
        val workspace = sessionWorkspace?.invoke(sessionId)
        val prompt = PromptRequest(
            model = preparedRoute?.remoteModelId ?: model,
            messages = emptyList(),
            tools = tools.ifEmpty { null },
            maxTokens = session.inferenceParams.withDefaults(config.inferenceDefaults()).maxTokens,
        )
        val context = contextBuilder.buildContext(ContextBuilderParams(
            sessionId = sessionId,
            content = lastUser?.content.orEmpty(),
            assistantMsgId = request.assistantMessageId,
            session = session,
            ragOptions = session.ragOptions,
            agentSystemPrompt = config.systemPrompt.takeIf(String::isNotBlank)
                ?: com.promenar.nexara.data.agent.PresetAgentPrompts.forAgentId(session.agentId),
            sessionCustomPrompt = session.customPrompt,
            agentRetrievalConfig = config.retrievalConfig,
            agentMemoryPolicy = config.memoryPolicy,
            availableToolNames = tools.mapTo(linkedSetOf()) { it.function.name },
            availableSkills = scanSkills(workspace),
            cachedRetrieval = com.promenar.nexara.ui.chat.manager.ContextRetrieval("", emptyList(), "", emptyList(), null, "", emptyList()),
            workspace = workspace,
            modelContextTokens = PreparedPromptBudgetGate.resolveWindow(session, prompt, settings, preparedRoute?.modelId).modelContextTokens,
        ))
        preparedContext = context
        preparedContextSummary = session.summary
        preparedPrompt = prompt
        return StoredContext(session, request, prompt, replaceHistorySummary(context.finalSystemPrompt, session.summary, session.summary))
    }

    private data class StoredContext(
        val session: com.promenar.nexara.data.model.Session,
        val request: GenerationRequest,
        val prompt: PromptRequest,
        val systemPrompt: String,
    )

    internal suspend fun compactNow(sessionId: String, onProgress: (ContextCompactionStage) -> Unit): ContextCompactionResult {
        manualCompaction = true
        val context = prepareStoredContext(sessionId)
        return compactContext(context.request, context.session, context.prompt, context.systemPrompt, manual = true, onProgress = onProgress)
    }

    internal suspend fun estimateContext(sessionId: String): ContextUsageEstimate {
        val context = prepareStoredContext(sessionId)
        val messages = buildMessagesWithMedia(context.session, context.systemPrompt).map { message ->
            if (message.role != "tool" || message.name == "view_media" || outputStore == null) message else message.copy(content = try {
                outputStore.previewForContext(sessionId, message.content)
            } catch (_: java.io.IOException) { ContextCompactionPipeline.headTail(message.content, 2000) })
        }
        val preview = compactionPipeline.preview(context.session, context.prompt.copy(messages = messages))
        val system = ApproximateTokenEstimator.estimate(
            context.prompt.copy(messages = listOf(com.promenar.nexara.data.remote.protocol.ProtocolMessage(
                "system", replaceHistorySummary(context.systemPrompt, null, context.session.summary),
            ))),
        )
        val summary = (ApproximateTokenEstimator.estimate(context.systemPrompt) -
            ApproximateTokenEstimator.estimate(replaceHistorySummary(context.systemPrompt, null, context.session.summary))).coerceAtLeast(0)
        val conversation = preview.prompt.messages.drop(if (preview.prompt.messages.firstOrNull()?.role == "system") 1 else 0).sumOf(ApproximateTokenEstimator::estimate)
        val rag = context.session.stats?.billing?.ragSystem?.count ?: 0
        val window = PreparedPromptBudgetGate.resolveWindow(context.session, context.prompt, settings, preparedRoute?.modelId)
        return ContextUsageEstimate(system, summary, conversation, rag, preview.foldedToolResults, window.modelContextTokens ?: 128000)
    }

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
            agentMemoryPolicy = agentConfig.memoryPolicy,
            workspace = sessionWorkspace?.invoke(request.sessionId),
        )
        var routeTools: List<ProtocolTool> = emptyList()
        val preparation = try {
            routeGate.prepareWithRoute(model) { route ->
                // Vertex 的多轮函数调用要求 thought signature 原样回传；夹具闭环前保持能力关闭。
                routeTools = if (route.supportsToolCalls) buildToolsOffMain(session, route.modelId) else emptyList()
                val toolNames = routeTools.mapTo(linkedSetOf()) { it.function.name }
                contextBuilder.buildContext(
                    params.copy(
                        availableToolNames = toolNames,
                        availableSkills = scanSkills(params.workspace),
                        modelContextTokens = PreparedPromptBudgetGate.resolveWindow(
                            session, PromptRequest(model = route.remoteModelId ?: model, messages = emptyList()), settings, route.modelId,
                        ).modelContextTokens,
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
        preparedContextSummary = session.summary
        preparedTools = tools
        val systemPrompt = if (finalAnswerMode) {
            context.finalSystemPrompt + FINAL_ANSWER_INSTRUCTION
        } else {
            context.finalSystemPrompt
        }
        val prompt = PromptRequest(
            messages = buildMessagesWithMedia(
                session,
                systemPrompt,
                request.userMessageId,
                request.messageIdsToReplace,
                route.modelId,
            ),
            model = requireNotNull(route.remoteModelId),
            temperature = inference.temperature,
            topP = inference.topP,
            maxTokens = inference.maxTokens,
            frequencyPenalty = inference.frequencyPenalty,
            presencePenalty = inference.presencePenalty,
            topK = inference.topK,
            repetitionPenalty = inference.repetitionPenalty,
            reasoningEffort = inference.resolvedReasoningEffort,
            tools = tools.ifEmpty { null },
            // 收尾轮仍声明工具（历史中的调用需要它们才能被部分协议解析），但禁止再发起调用。
            toolChoice = if (finalAnswerMode) ProtocolToolChoice.NONE else ProtocolToolChoice.AUTO,
            webSearch = session.options.webSearch,
            enableGeminiSearch = session.options.enableGeminiSearch,
            stream = true,
            streamTimeout = (inference.streamTimeout ?: 120).toLong() * 1000,
        )
        val budgetedPrompt = compactContext(request, session, prompt, systemPrompt).prompt
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
        val prepared = requireNotNull(preparedPrompt) { "生成请求尚未准备完成" }
        // 网络重试不会重建上下文；每次真正发起请求前都要重新校验工作区媒体。
        val prompt = if (mediaContextLoader == null) prepared else {
            val currentSession = store.getSession(request.sessionId)
                ?: com.promenar.nexara.data.model.Session(request.sessionId, "")
            prepared.copy(messages = mediaContextLoader.attach(currentSession, prepared.messages, route.modelId))
        }
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
                    reasoningEffort = prompt.reasoningEffort,
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
            // 基于最新视图追加，避免覆盖上一轮工具调用尚未刷入 store 的执行步骤。
            messageManager.updateFromLatest(request.sessionId, assistantMessageId(request)) { current ->
                UpdateMessageOptions(executionSteps = current.executionSteps.orEmpty() + normalized.syntheticSteps)
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
                thoughtSignature = snapshot.reasoningReplay,
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
            cacheReadInput = snapshot.cacheReadInputTokens,
            cacheWriteInput = snapshot.cacheWriteInputTokens,
            reasoningOutput = snapshot.reasoningOutputTokens,
            timing = snapshot.timing?.let {
                com.promenar.nexara.data.model.GenerationTimingRecord(it.requestStartedAt, it.firstOutputAt, it.endedAt)
            } ?: existing.timing,
        )
    }

    private suspend fun recordSummaryUsage(request: GenerationRequest, usage: TokenUsage) {
        summarySpentTokens += usage.total
        summaryUsageEstimated = summaryUsageEstimated || usage.estimated
        val message = if (manualCompaction) null else store.getSession(request.sessionId)?.messages?.firstOrNull { it.id == assistantMessageId(request) }
        if (message == null) {
            // 没有助手消息的手动任务将费用写入既有摘要账本，不创建会话占位消息。
            val record = RolledBackSummaryUsageRecord(
                messageId = "manual-summary-${java.util.UUID.randomUUID()}",
                sessionId = request.sessionId,
                modelId = preparedRoute?.modelId ?: preparedPrompt?.model.orEmpty(),
                usage = usage,
                createdAt = System.currentTimeMillis(),
            )
            RolledBackSummaryUsageStore(settings).preserveBeforeDiscard(listOf(record)) {}
            return
        }
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

    /** 子代理用量计入父会话本轮：并入助手消息的附加用量，不计入主模型的速度统计。 */
    private suspend fun recordSubagentUsage(request: GenerationRequest) {
        val usage = subagentUsage?.drain(request.sessionId) ?: return
        // 取出即结算：写入在不可取消区内完成，取消不会让同一笔用量被重复计入。
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { recordSummaryUsage(request, usage) }
        } catch (error: Exception) {
            NexaraLogger.logError("[GenerationRuntime] 子代理用量写入失败", error)
        }
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
        val preparedByName = preparedTools.groupBy { it.function.name }
        val targets = calls.associate { call ->
            val tool = preparedByName[call.name]?.singleOrNull()
            call.id to tool?.let { resolveSessionToolTarget(session, it, call.arguments, workspaceRepository, externalFolders, sshRuntime?.hostStore, sshRuntime?.let { runtime -> runtime::sessionHost }) }
        }
        // UUID 必须先还原当前虚拟路径；技能目录变更在全自动模式也不能跳过审批。
        val policyPendingIds = contentStrategy
            .pendingApprovalIds(calls, session.executionMode.ifEmpty { "semi" })
            .toMutableSet().apply {
                // 文件变更的目标无法还原时，无从判断是否落在技能目录，按失败关闭要求审批。
                calls.filter {
                    com.promenar.nexara.domain.tool.ToolArgumentApprovalRules.requiresExplicitApproval(it.name, it.arguments, targets[it.id]) ||
                        com.promenar.nexara.domain.tool.ToolExecutionTargets.isPathMutation(it.name) && targets[it.id] == null
                }
                    .forEach { add(it.id) }
            }
        val requestedPendingIds = policyPendingIds.filterTo(mutableSetOf()) { id ->
            val call = calls.first { it.id == id }
            val target = targets[id]
            val scope = target?.let {
                com.promenar.nexara.data.repository.ToolAllowlistScopes.resolveForSession(session.id, it, sessionWorkspace, externalFolders)
            }
            target == null || scope == null || toolAllowlistStore?.contains(call.name, target, scope.scopeKey) != true
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
        try {
            toolExecutor.executeTools(
                request.sessionId,
                assistantMessageId(request),
                calls,
                allowedIds,
                preparedTools,
                expectedGrantTargets = grantTargets,
            )
        } finally {
            // 停止本轮时子代理已消耗的用量同样计入本轮，不留给下一轮。
            recordSubagentUsage(request)
        }
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

    override fun maxStreamRetries(request: GenerationRequest): Int =
        settings.getInt(STREAM_RETRY_LIMIT_KEY, DEFAULT_STREAM_RETRY_LIMIT).coerceIn(0, MAX_STREAM_RETRY_LIMIT)

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
        // 审批通过后由审批流程执行的子代理，用量在本轮结束时汇入。
        recordSubagentUsage(request)
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
            // 未配置嵌入模型时不写入也不显示写入状态，由嵌入模型设置提示引导用户。
            if (memoryEnabled(session) && request.userContent.isNotBlank() &&
                snapshot.content.isNotBlank() && memory != null && memory.canEmbed
            ) {
                val turnMessageId = assistantMessageId(request)
                messageManager.writeTurnMemory(request.sessionId, turnMessageId, previous = null) {
                    val written = memory.addTurnToMemory(
                        request.sessionId,
                        request.userContent,
                        snapshot.content,
                        userMessageId,
                        turnMessageId,
                    )
                    if (written > 0) TurnMemoryState(TurnMemoryState.Status.STORED, written, System.currentTimeMillis()) else null
                }
            }
            // 图谱抽取只需要抽取模型，不依赖嵌入模型；会话关闭记忆时同样不抽取对话内容。
            val graph = sessionGraph
            if (graph != null && snapshot.failure == null && memoryEnabled(session) &&
                request.userContent.isNotBlank() && snapshot.content.isNotBlank()
            ) {
                try {
                    graph.schedule(request.sessionId, assistantMessageId(request), userMessageId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    NexaraLogger.logError("[GenerationRuntime] 会话图谱抽取排队失败", error)
                }
            }
            // 轮后按下一次请求实际发送的未摘要历史估算，不使用累计账单 token 触发摘要。
            val nextPrompt = (preparedPrompt ?: PromptRequest(model = session.modelId.orEmpty(), messages = emptyList()))
            compactContext(request, store.getSession(request.sessionId) ?: session, nextPrompt, context.finalSystemPrompt)
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
                discardPreparedMessagesPreservingSummaryUsage(
                    request,
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

    /**
     * 本轮是否使用对话记忆：按检索时实际生效的选项判断；会话未保存检索选项（null）时即为默认选项，记忆开启。
     */
    private fun memoryEnabled(session: com.promenar.nexara.data.model.Session): Boolean =
        preparedContext?.retrieval?.effectiveRagOptions?.enableMemory
            ?: (session.ragOptions ?: RagOptions()).enableMemory

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
            discardPreparedMessagesPreservingSummaryUsage(
                request,
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
            discardPreparedMessagesPreservingSummaryUsage(
                request,
                messagesCreatedSincePrepare(request),
            )
        } else if (toolRounds == 0) {
            val preparedIds = listOfNotNull(
                assistantMessageId(request),
                request.userMessageId.takeIf { request.rollbackUserOnPreparationFailure },
            )
            discardPreparedMessagesPreservingSummaryUsage(request, preparedIds)
        }
    }

    private suspend fun discardPreparedMessagesPreservingSummaryUsage(request: GenerationRequest, messageIds: List<String>) {
        val session = store.getSession(request.sessionId)
        val ids = messageIds.toSet()
        val usageRecords = session?.messages.orEmpty().filter { it.id in ids }.mapNotNull { message ->
            val tokens = message.tokens ?: return@mapNotNull null
            if (tokens.summaryInput <= 0 && tokens.summaryOutput <= 0 && tokens.summaryTotal <= 0) return@mapNotNull null
            RolledBackSummaryUsageRecord(
                messageId = message.id,
                sessionId = request.sessionId,
                modelId = message.modelId ?: session?.modelId ?: "unknown",
                usage = TokenUsage(tokens.summaryInput, tokens.summaryOutput, tokens.summaryTotal, tokens.summaryEstimated),
                createdAt = message.createdAt,
            )
        }
        // 无摘要费用时保留原调度；已有费用的补偿重试仍须与统计读取串行。
        if (usageRecords.isEmpty() && summarySpentTokens == 0L) {
            messageManager.discardPreparedMessages(request.sessionId, messageIds)
            return
        }
        RolledBackSummaryUsageStore(settings).preserveBeforeDiscard(usageRecords) {
            messageManager.discardPreparedMessages(request.sessionId, messageIds)
        }
    }

    private suspend fun archiveMessages(
        request: GenerationRequest,
        modelId: String,
        messages: List<com.promenar.nexara.data.model.Message>,
    ) {
        val taskId = ui.addPostProcessTask(PostProcessType.ARCHIVE_TO_RAG, "")
        try {
            postProcessor.archiveMessagesToRag(request.sessionId, messages, modelId) { progress, detail ->
                ui.updatePostProcessTask(taskId, progress = progress)
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
        val taskId = ui.addPostProcessTask(PostProcessType.AUTO_SUMMARY, "")
        return try {
            var usage: TokenUsage? = null
            val summary = try {
                summaryManager.summarizeOrNull(
                    session.summary,
                    messages,
                    preparedRoute?.modelId ?: preparedPrompt?.model ?: session.modelId.orEmpty(),
                    onProgress = { ui.updatePostProcessTask(taskId, progress = 0.5f) },
                    onUsage = { usage = it },
                )
            } finally {
                usage?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { recordSummaryUsage(request, it) } }
            }
            if (summary.isNullOrBlank()) {
                ui.updatePostProcessTask(taskId, PostProcessStatus.ERROR, detail = null)
                return false
            }
            sessionManager.commitContextCompaction(
                request.sessionId, summary, messages.map { it.id },
                com.promenar.nexara.data.model.ContextCompactionSource(session.summary, messages.toList()),
            )
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

    private fun toGenerationChunk(chunk: StreamChunk): GenerationChunk? = when (chunk) {
        is StreamChunk.TextDelta -> GenerationChunk.Text(chunk.content, chunk.reasoning)
        is StreamChunk.Thinking -> GenerationChunk.Thinking(chunk.content)
        is StreamChunk.ToolCallDelta -> GenerationChunk.ToolCall(chunk.id, chunk.name, chunk.arguments)
        is StreamChunk.Usage -> GenerationChunk.Usage(
            input = chunk.usage.input,
            output = chunk.usage.output,
            total = chunk.usage.total,
            cacheReadInput = chunk.usage.cacheReadInput,
            cacheWriteInput = chunk.usage.cacheWriteInput,
            reasoningOutput = chunk.usage.reasoningOutput,
        )
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
                retryable = chunk.retryable,
            ),
        )
        is StreamChunk.Completed -> GenerationChunk.Completed(chunk.reason, chunk.completedToolCallIds)
        is StreamChunk.ToolCallLifecycle -> null
        is StreamChunk.ReasoningState -> preparedRoute?.modelId?.takeIf(String::isNotBlank)?.let { source ->
            GenerationChunk.ReasoningState(ReasoningReplayCodec.encode(source, chunk.replay))
        }
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
