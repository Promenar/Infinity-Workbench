package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.data.model.InferenceParams
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
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.ToolApprovalCreation
import com.promenar.nexara.data.repository.ToolExecutionLedger
import com.promenar.nexara.domain.generation.GenerationChunk
import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationFailureCode
import com.promenar.nexara.domain.generation.GenerationFailureCodec
import com.promenar.nexara.domain.generation.GenerationPreparationOutcome
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationSnapshot
import com.promenar.nexara.domain.generation.GenerationTerminalStatus
import com.promenar.nexara.domain.generation.GenerationToolCall
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface GenerationUiPort {
    fun replaceRagPhases(phases: List<RagPhase>)
    fun updateRagPhases(transform: (List<RagPhase>) -> List<RagPhase>)
    fun setGenerating(value: Boolean)
    fun setStreamingContent(value: String)
    fun setError(failure: GenerationFailure?)
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

private fun ContextBuilderResult.hasPersistableRagContext(): Boolean =
    ragContext.isNotBlank() || ragReferences.isNotEmpty() || citations.isNotEmpty() ||
        kgPaths.isNotEmpty() || ragUsage != null

private fun ContextBuilderResult.toMessageRagUpdateOptions(): UpdateMessageOptions? {
    if (ragReferences.isEmpty() && citations.isEmpty() && kgPaths.isEmpty()) return null
    val hasReferenceMetadata = ragReferences.isNotEmpty() || citations.isNotEmpty()
    return UpdateMessageOptions(
        ragReferences = ragReferences.ifEmpty { null },
        citations = citations.ifEmpty { null },
        kgPaths = kgPaths.ifEmpty { null },
        ragMetadata = if (hasReferenceMetadata) {
            com.promenar.nexara.data.model.RagMetadata(
                chunkCount = ragReferences.size,
                totalTokens = ragUsage?.ragSystem ?: 0,
                retrievalTimeMs = 0,
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
) : ChatGenerationRuntime {
    private var preparedRoute: ChatProviderRoute? = null
    private var preparedContext: ContextBuilderResult? = null
    private var preparedPrompt: PromptRequest? = null
    private var preparedTools: List<ProtocolTool> = emptyList()
    private var toolRounds = 0
    private var currentAssistantMessageId: String? = null

    override suspend fun prepare(request: GenerationRequest) {
        currentAssistantMessageId = request.assistantMessageId
        require(store.getSession(request.sessionId)?.messages?.any {
            it.id == assistantMessageId(request) && it.role == MessageRole.ASSISTANT
        } == true) { "生成目标消息不存在" }
        ui.setGenerating(true)
    }

    override suspend fun buildContext(request: GenerationRequest): GenerationPreparationOutcome {
        ui.replaceRagPhases(defaultRagPhases())
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
            agentSystemPrompt = agentConfig.systemPrompt,
            sessionCustomPrompt = session.customPrompt,
            agentRetrievalConfig = agentConfig.retrievalConfig,
        )
        val preparation = try {
            routeGate.prepare(model) { contextBuilder.buildContext(params) }
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
        val tools = contentStrategy.buildTools(session)
        val inference = session.inferenceParams ?: InferenceParams(
            temperature = agentConfig.temperature,
            topP = agentConfig.topP,
            maxTokens = agentConfig.maxTokens,
        )
        preparedRoute = route
        preparedContext = context
        preparedTools = tools
        preparedPrompt = PromptRequest(
            messages = contentStrategy.buildProtocolMessages(session, context.finalSystemPrompt),
            model = requireNotNull(route.remoteModelId),
            temperature = inference.temperature,
            topP = inference.topP,
            maxTokens = inference.maxTokens,
            frequencyPenalty = inference.frequencyPenalty,
            presencePenalty = inference.presencePenalty,
            topK = inference.topK,
            repetitionPenalty = inference.repetitionPenalty,
            tools = tools.ifEmpty { null },
            webSearch = session.options.webSearch,
            enableGeminiSearch = session.options.enableGeminiSearch,
            stream = true,
            streamTimeout = (inference.streamTimeout ?: 120).toLong() * 1000,
        )
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
                    enableWebSearch = prompt.webSearch == true,
                ),
                StreamConfig(enableWebSearch = prompt.webSearch == true),
            )
        }
        return source.map(::toGenerationChunk)
    }

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
        if (current.isNotEmpty() && current.all { it.name.isNotEmpty() && it.arguments.isNotEmpty() }) return snapshot
        if (snapshot.content.isBlank()) return snapshot
        val fallback = contentStrategy.extractFallbackToolCalls(snapshot.content)
        if (fallback.isEmpty()) return snapshot
        return snapshot.copy(
            content = contentStrategy.stripToolCallMarkup(snapshot.content),
            toolCalls = contentStrategy.mergeToolCalls(current, fallback).map {
                GenerationToolCall(it.id, it.name, it.arguments)
            },
        )
    }

    override suspend fun persist(request: GenerationRequest, snapshot: GenerationSnapshot) {
        val calls = snapshot.toolCalls.map { ToolCall(it.id, it.name, it.arguments) }
        val failureEnvelope = snapshot.failure?.let(GenerationFailureCodec::encode)
        ui.setError(snapshot.failure)
        ui.setStreamingContent(snapshot.content)
        messageManager.updateMessageContent(
            request.sessionId,
            assistantMessageId(request),
            snapshot.content,
            UpdateMessageOptions(
                reasoning = snapshot.reasoning.ifBlank { null },
                tokens = TokenUsage(snapshot.inputTokens, snapshot.outputTokens, snapshot.totalTokens),
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

    override suspend fun handleTools(
        request: GenerationRequest,
        toolCalls: List<GenerationToolCall>,
    ): GenerationToolDecision {
        val session = store.getSession(request.sessionId) ?: error("生成会话不存在")
        messageManager.flushGenerationTerminal(request.sessionId, assistantMessageId(request))
        val calls = toolCalls.map { ToolCall(it.id, it.name, it.arguments) }
        val pendingIds = contentStrategy.pendingApprovalIds(calls, session.executionMode.ifEmpty { "semi" })
        if (pendingIds.isNotEmpty()) {
            val first = calls.firstOrNull { it.id in pendingIds }
            val approval = ApprovalRequest(
                toolName = first?.name,
                args = first?.arguments,
                reason = "Execution mode: ${session.executionMode.ifEmpty { "semi" }}",
                type = "tool_approval",
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
            toolExecutor.executeTools(
                request.sessionId,
                assistantMessageId(request),
                calls,
                calls.map { it.id }.filterNot { it in pendingIds }.toSet(),
            )
            ui.setGenerating(false)
            return GenerationToolDecision.WAIT_FOR_APPROVAL
        }
        val loopLimit = settings.getInt("loop_limit", 50)
        check(toolRounds++ < loopLimit) { "工具循环达到上限 $loopLimit" }
        toolExecutor.executeTools(request.sessionId, assistantMessageId(request), calls)
        return GenerationToolDecision.CONTINUE
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
            val windowSize = session.inferenceParams?.activeContextWindow ?: 10
            if (session.messages.size <= windowSize) return
            val activeIds = contentStrategy.safeActiveWindow(session.messages, windowSize).map { it.id }.toSet()
            val overflow = session.messages.filter { it.id !in activeIds && !it.isArchived }
            if (overflow.isEmpty()) return
            if (rag?.enableMemory == true) archiveMessages(request, session.modelId.orEmpty(), overflow)
            overflow.forEach { message ->
                messageManager.updateMessage(request.sessionId, message.id, message.copy(isArchived = true))
            }
            val threshold = session.inferenceParams?.autoSummaryThreshold ?: 0.8
            val maxTokens = findModelSpec(session.modelId.orEmpty())?.contextLength ?: 128000
            if (snapshot.totalTokens > maxTokens * threshold) summarize(request, session, overflow)
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
    }

    override suspend fun flush(request: GenerationRequest) =
        messageManager.flushGenerationTerminal(request.sessionId, assistantMessageId(request))

    override fun cancelProvider() = provider.cancel()

    private fun assistantMessageId(request: GenerationRequest): String =
        currentAssistantMessageId ?: request.assistantMessageId

    private suspend fun handlePreparationFailure(
        request: GenerationRequest,
        generationFailure: GenerationFailure,
        failure: ProviderResolution.Failure?,
    ) {
        ui.setError(generationFailure)
        ui.setProviderFailure(failure)
        ui.setGenerating(false)
        ui.onHandledFailure()
        messageManager.markGenerationTerminal(
            request.sessionId,
            assistantMessageId(request),
            "",
            "error",
            GenerationFailureCodec.encode(generationFailure),
        )
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
    ) {
        val taskId = ui.addPostProcessTask(PostProcessType.AUTO_SUMMARY, "Summarizing conversation")
        try {
            val summary = summaryManager.summarize(
                session.summary,
                messages,
                settings.getString("preset_summary_model", ""),
                session.modelId.orEmpty(),
            ) { ui.updatePostProcessTask(taskId, detail = it) }
            if (summary != session.summary) sessionManager.updateSession(request.sessionId, mapOf("summary" to summary))
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

    private fun toGenerationChunk(chunk: StreamChunk): GenerationChunk = when (chunk) {
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
        is StreamChunk.ToolCallLifecycle, StreamChunk.Done -> GenerationChunk.Done
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
}
