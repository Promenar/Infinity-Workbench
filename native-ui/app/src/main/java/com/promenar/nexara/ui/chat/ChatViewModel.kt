package com.promenar.nexara.ui.chat

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.data.model.InferenceParams
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.PostProcessStatus
import com.promenar.nexara.data.model.PostProcessTask
import com.promenar.nexara.data.model.PostProcessType
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.SessionOptions
import com.promenar.nexara.R
import com.promenar.nexara.data.model.TokenUsage
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.UpdateMessageOptions
import com.promenar.nexara.data.model.RagMetadata
import com.promenar.nexara.data.model.RagPhase
import com.promenar.nexara.data.model.RagProgress
import com.promenar.nexara.utils.NexaraLogger
import com.promenar.nexara.data.model.PhaseStatus
import com.promenar.nexara.data.model.findModelSpec
import com.promenar.nexara.domain.usecase.AgentConfigResolver
import com.promenar.nexara.domain.usecase.ExportSessionUseCase
import com.promenar.nexara.domain.usecase.IdGenerator
import com.promenar.nexara.data.rag.EmbeddingClient
import com.promenar.nexara.data.rag.MemoryManager
import com.promenar.nexara.data.rag.MemoryManagerRagAdapter
import com.promenar.nexara.data.rag.RecursiveCharacterTextSplitter
import com.promenar.nexara.data.rag.VectorStore
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ImageInput
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.UnifiedLlmClient
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.domain.repository.IAgentRepository
import com.promenar.nexara.ui.chat.manager.ApprovalManager
import com.promenar.nexara.ui.chat.manager.ContextBuilder
import com.promenar.nexara.ui.chat.manager.ContextBuilderParams
import com.promenar.nexara.ui.chat.manager.KgProvider
import com.promenar.nexara.ui.chat.manager.MessageManager
import com.promenar.nexara.ui.chat.manager.PostProcessor
import com.promenar.nexara.ui.chat.manager.SessionManager
import com.promenar.nexara.ui.chat.manager.SummaryManager
import com.promenar.nexara.ui.chat.manager.ToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

enum class GenerationStatus {
    IDLE,
    UPLOADING,
    THINKING,
    RECEIVING,
    COMPLETED,
    ERROR
}

data class ChatUiState(
    val session: Session? = null,
    val agentName: String = "",
    val messages: List<Message> = emptyList(),
    val isGenerating: Boolean = false,
    val isLoading: Boolean = false,
    val status: GenerationStatus = GenerationStatus.IDLE,
    val streamingContent: String = "",
    val error: String? = null,
    val approvalRequest: ApprovalRequest? = null
)

class ChatViewModel(
    private val application: Application,
    private val sessionRepository: ISessionRepository,
    private val messageRepository: IMessageRepository,
    private val agentRepository: IAgentRepository,
    private val llmProvider: LlmProvider,
    private val unifiedLlmClient: UnifiedLlmClient? = null,
    private val configResolver: AgentConfigResolver,
    private val embeddingClient: EmbeddingClient? = null,
    private val vectorStore: VectorStore? = null,
    private val textSplitter: RecursiveCharacterTextSplitter? = null,
    private val memoryManager: MemoryManager? = null,
    private val kgProvider: KgProvider? = null,
    private val skillRegistry: com.promenar.nexara.ui.chat.manager.registry.SkillRegistry? = null,
    private val exportSessionUseCase: ExportSessionUseCase? = null
) : ViewModel() {

    private val store = (application as NexaraApplication).chatStore

    private val sessionManager = SessionManager(store, sessionRepository)
    private val messageManager = MessageManager(store, messageRepository, sessionRepository, viewModelScope)
    private val webSearchContextProvider = (application as NexaraApplication).webSearchContextProvider

    private val contextBuilder = ContextBuilder(
        webSearchProvider = webSearchContextProvider,
        ragProvider = memoryManager?.let { MemoryManagerRagAdapter(it) },
        kgProvider = kgProvider,
        taskRepository = (application as NexaraApplication).taskRepository
    )
    private val toolExecutor = ToolExecutor(store, messageManager, skillRegistry, (application as NexaraApplication).taskRepository)
    private val postProcessor = PostProcessor(store, sessionManager, messageManager, embeddingClient, vectorStore, textSplitter)
    private val summaryManager = SummaryManager(llmProvider)
    private val approvalManager = ApprovalManager(store)

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    private val _currentSessionId = MutableStateFlow<String?>(null)
    private val _agentName = MutableStateFlow("")

    private val _streamingContent = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)
    private val _isGenerating = MutableStateFlow(false)
    private val _isLoading = MutableStateFlow(false)
    private val _generationStatus = MutableStateFlow(GenerationStatus.IDLE)

    data class TokenIndicatorState(
        val used: Int = 0,
        val max: Int = 128000,
        val systemTokens: Int = 0,
        val summaryTokens: Int = 0,
        val activeTokens: Int = 0,
        val ragTokens: Int = 0
    )
    private val _tokenIndicatorState = MutableStateFlow(TokenIndicatorState())
    val tokenIndicatorState: StateFlow<TokenIndicatorState> = _tokenIndicatorState

    private val _ragPhases = MutableStateFlow<List<RagPhase>>(emptyList())
    val ragPhases: StateFlow<List<RagPhase>> = _ragPhases

    data class CompressionState(
        val isCompressing: Boolean = false,
        val progress: Float = 0f,
        val detail: String = "",
        val result: String? = null
    )

    private val _compressionState = MutableStateFlow(CompressionState())
    val compressionState: StateFlow<CompressionState> = _compressionState

    private val _postProcessTasks = MutableStateFlow<List<PostProcessTask>>(emptyList())
    val postProcessTasks: StateFlow<List<PostProcessTask>> = _postProcessTasks

    fun addPostProcessTask(type: PostProcessType, status: PostProcessStatus = PostProcessStatus.RUNNING, progress: Float = 0f, detail: String = ""): String {
        val id = "pp_${System.currentTimeMillis()}_${type.ordinal}"
        val task = PostProcessTask(id = id, type = type, status = status, progress = progress, detail = detail)
        _postProcessTasks.update { it + task }
        return id
    }

    fun updatePostProcessTask(id: String, status: PostProcessStatus? = null, progress: Float? = null, detail: String? = null) {
        _postProcessTasks.update { tasks ->
            tasks.map { if (it.id == id) it.copy(status = status ?: it.status, progress = progress ?: it.progress, detail = detail ?: it.detail) else it }
        }
    }

    fun removePostProcessTask(id: String) {
        _postProcessTasks.update { tasks -> tasks.filter { it.id != id } }
    }

    private var generationJob: Job? = null

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<ChatUiState> = combine(
        store.state,
        _currentSessionId,
        _agentName,
        _isGenerating,
        _isLoading,
        _generationStatus,
        _streamingContent,
        _error
    ) { args: Array<Any?> ->
        val state = args[0] as com.promenar.nexara.ui.chat.ChatState
        val sessionId = args[1] as String?
        val agentName = args[2] as String
        val isGenerating = args[3] as Boolean
        val isLoading = args[4] as Boolean
        val status = args[5] as GenerationStatus
        val streamingContent = args[6] as String
        val error = args[7] as String?

        val session = state.sessions.find { it.id == sessionId }
        if (session != null) {
            updateTokenIndicator(session)
        }
        ChatUiState(
            session = session,
            agentName = agentName,
            messages = session?.messages ?: emptyList(),
            isGenerating = isGenerating,
            isLoading = isLoading,
            status = status,
            streamingContent = streamingContent,
            error = error,
            approvalRequest = session?.approvalRequest
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, ChatUiState())

    init {
        approvalManager.setCallbacks(
            onGenerateMessage = { sessionId, content, isResumption ->
                generateMessage(sessionId, content, isResumption)
            },
            onExecuteTools = { sessionId, targetMessageId ->
                val session = store.getSession(sessionId) ?: return@setCallbacks
                val msg = session.messages.find { it.id == targetMessageId } ?: return@setCallbacks
                if (msg.toolCalls != null) {
                    toolExecutor.executeTools(sessionId, msg.toolCalls, targetMessageId)
                }
            }
        )
    }

    fun updateInputText(text: String) {
        _inputText.update { text }
    }

    fun sendMessage(text: String, imageUris: List<Uri> = emptyList()) {
        val sessionId = _currentSessionId.value
        if (sessionId == null) return
        if (text.isBlank() && imageUris.isEmpty()) return

        val session = store.getSession(sessionId) ?: return
        val userMsgId = IdGenerator.message("user")
        val assistantMsgId = IdGenerator.message("ai")

        _inputText.update { "" }
        _error.update { null }
        viewModelScope.launch { sessionManager.updateSessionDraft(sessionId, null) }

        val imageDataUrls = imageUris.mapNotNull { uri ->
            try {
                val bytes = application.contentResolver.openInputStream(uri)?.readBytes()
                val mimeType = application.contentResolver.getType(uri) ?: "image/jpeg"
                bytes?.let { "data:$mimeType;base64,${Base64.encodeToString(it, Base64.NO_WRAP)}" }
            } catch (_: Exception) { null }
        }

        cancelActiveGeneration()
        generationJob = viewModelScope.launch {
            val userMessage = Message(
                id = userMsgId,
                role = MessageRole.USER,
                content = text,
                userImages = imageDataUrls.ifEmpty { null },
                createdAt = System.currentTimeMillis()
            )

            messageManager.addMessage(sessionId, userMessage)

            val assistantMessage = Message(
                id = assistantMsgId,
                role = MessageRole.ASSISTANT,
                content = "",
                modelId = session.modelId,
                createdAt = System.currentTimeMillis()
            )
            messageManager.addMessage(sessionId, assistantMessage)

            generateMessage(sessionId, "", false, assistantMsgId, userMsgId, text)
        }
    }

    private suspend fun generateMessage(
        sessionId: String,
        interventionContent: String,
        isResumption: Boolean,
        existingAssistantMsgId: String? = null,
        userMsgId: String? = null,
        userContent: String? = null,
        loopCount: Int = 0
    ) {
        val session = store.getSession(sessionId) ?: return
        val prefs = application.getSharedPreferences("nexara_settings", 0)
        val effectiveLoopLimit = prefs.getInt("loop_limit", 15)
        if (loopCount >= effectiveLoopLimit) return

        val assistantMsgId = existingAssistantMsgId
            ?: session.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
            ?: return

        val sessionForCtx = store.getSession(sessionId) ?: return

        val effectiveUserContent = userContent
            ?: interventionContent.ifBlank { sessionForCtx.pendingIntervention }
            ?: sessionForCtx.messages.lastOrNull { it.role == MessageRole.USER }?.content
            ?: ""

        _isGenerating.update { true }
        _generationStatus.update { GenerationStatus.UPLOADING }
        _streamingContent.update { "" }
        _error.update { null }

        try {
        val defaultPhases = listOf(
            RagPhase("embed", "Embedding query", PhaseStatus.PENDING),
            RagPhase("memory", "Searching memory", PhaseStatus.PENDING),
            RagPhase("docs", "Searching documents", PhaseStatus.PENDING),
            RagPhase("hybrid", "Hybrid fusion", PhaseStatus.PENDING),
            RagPhase("rank", "Ranking results", PhaseStatus.PENDING),
            RagPhase("rerank", "Reranking", PhaseStatus.PENDING),
            RagPhase("kg", "KG retrieval", PhaseStatus.PENDING),
            RagPhase("ready", "Context ready", PhaseStatus.PENDING)
        )
        _ragPhases.update { defaultPhases }

        val agent = agentRepository.getById(sessionForCtx.agentId)
        val agentConfig = configResolver.resolve(agent)

        // 使用缓存的 ragOptions，确保与用户最新设置一致（绕过 store 异步延迟）
        val effectiveRagOptions = _currentRagOptions.value.let { cached ->
            val sessionOpts = sessionForCtx.ragOptions
            if (sessionOpts != null && sessionOpts != cached) {
                // 回退：如果 session 中有值但与缓存不同，以缓存为准
                cached
            } else if (sessionOpts != null) {
                sessionOpts
            } else {
                cached
            }
        }
        NexaraLogger.log("[ChatViewModel] generateMessage ragOptions: enableMemory=${effectiveRagOptions.enableMemory}, enableDocs=${effectiveRagOptions.enableDocs}, isGlobal=${effectiveRagOptions.isGlobal}")

        val contextParams = ContextBuilderParams(
            sessionId = sessionId,
            content = effectiveUserContent,
            assistantMsgId = assistantMsgId,
            session = sessionForCtx,
            ragOptions = effectiveRagOptions,  // 显式传递，确保生效
            onRagProgress = { stage, percentage, subStage ->
                messageManager.updateMessageProgress(
                    sessionId, assistantMsgId,
                    RagProgress(stage = stage, percentage = percentage, subStage = subStage)
                )
                _ragPhases.update { phases ->
                    val phaseId = when {
                        stage.contains("Embedding", ignoreCase = true) -> "embed"
                        stage.contains("memory", ignoreCase = true) || stage.contains("Searching memory", ignoreCase = true) -> "memory"
                        stage.contains("document", ignoreCase = true) || stage.contains("Searching documents", ignoreCase = true) -> "docs"
                        stage.contains("Hybrid", ignoreCase = true) || stage.contains("fusion", ignoreCase = true) -> "hybrid"
                        stage.contains("Ranking", ignoreCase = true) || stage.contains("Rank", ignoreCase = true) -> "rank"
                        stage.contains("Rerank", ignoreCase = true) -> "rerank"
                        stage.contains("KG", ignoreCase = true) -> "kg"
                        stage.contains("Context ready", ignoreCase = true) -> "ready"
                        else -> null
                    }
                    if (phaseId == null) return@update phases
                    val idx = phases.indexOfFirst { it.id == phaseId }
                    if (idx < 0) return@update phases
                    phases.mapIndexed { i, p ->
                        if (i == idx) p.copy(
                            status = PhaseStatus.ACTIVE,
                            progress = percentage,
                            detail = subStage
                        )
                        else if (i < idx && p.status == PhaseStatus.ACTIVE) p.copy(status = PhaseStatus.DONE)
                        else if (phaseId == "ready" && i == idx) p.copy(status = PhaseStatus.DONE, progress = 100)
                        else p
                    }
                }
            },
            agentSystemPrompt = agentConfig.systemPrompt,
            sessionCustomPrompt = sessionForCtx.customPrompt
        )

        val contextResult = try {
            contextBuilder.buildContext(contextParams)
        } catch (e: Exception) {
            _error.update { "Context build failed: ${e.message}" }
            _ragPhases.update { phases -> phases.map { p -> if (p.status == PhaseStatus.ACTIVE) p.copy(status = PhaseStatus.DONE) else p } }
            _isGenerating.update { false }
            _generationStatus.update { GenerationStatus.ERROR }
            viewModelScope.launch {
                kotlinx.coroutines.delay(2000)
                _generationStatus.update { GenerationStatus.IDLE }
            }
            return
        }

        // P0 修复: 仅将真正执行过的 ACTIVE 阶段标记为 DONE；未触发的 PENDING 阶段保持原状
        // 原逻辑: phases.map { if (p.status != DONE) p.copy(status = DONE) } — 批量假完成
        _ragPhases.update { phases ->
            val executedPhaseIds = phases.filter { it.status == PhaseStatus.ACTIVE || it.status == PhaseStatus.DONE }.map { it.id }.toSet()
            phases.map { p ->
                when {
                    // 已执行 (ACTIVE→DONE) 或已完成的保持 DONE
                    p.id in executedPhaseIds && p.status != PhaseStatus.DONE -> p.copy(status = PhaseStatus.DONE)
                    // 未执行 (PENDING) 保持 PENDING，不批量升级
                    else -> p
                }
            }
        }

        if (contextResult.ragReferences.isNotEmpty()) {
            messageManager.updateMessageContent(
                sessionId, assistantMsgId, "",
                UpdateMessageOptions(
                    ragReferences = contextResult.ragReferences,
                    ragMetadata = RagMetadata(
                        chunkCount = contextResult.ragReferences.size,
                        totalTokens = contextResult.ragUsage?.ragSystem ?: 0,
                        retrievalTimeMs = 0
                    )
                )
            )
        }

        val protocolMessages = buildProtocolMessages(sessionForCtx, contextResult.finalSystemPrompt)

        val activeTools = buildToolList(sessionForCtx)

        val effectiveModel = sessionForCtx.modelId
            ?: agentConfig.modelId

        val effectiveParams = sessionForCtx.inferenceParams ?: InferenceParams(
            temperature = agentConfig.temperature,
            topP = agentConfig.topP,
            maxTokens = agentConfig.maxTokens
        )

        val request = PromptRequest(
            messages = protocolMessages,
            model = effectiveModel,
            temperature = effectiveParams.temperature,
            topP = effectiveParams.topP,
            maxTokens = effectiveParams.maxTokens,
            frequencyPenalty = effectiveParams.frequencyPenalty,
            presencePenalty = effectiveParams.presencePenalty,
            topK = effectiveParams.topK,
            repetitionPenalty = effectiveParams.repetitionPenalty,
            tools = activeTools.ifEmpty { null },
            webSearch = sessionForCtx.options.webSearch,
            stream = true,
            streamTimeout = (effectiveParams.streamTimeout ?: 120).toLong() * 1000
        )

        var accumulatedContent = ""
        var accumulatedReasoning = ""
        var accumulatedTokens = TokenUsage()
        val accumulatedToolCalls = mutableListOf<ToolCall>()

        try {
            val flow = if (unifiedLlmClient != null) {
                val stParams = com.promenar.nexara.data.remote.middleware.StreamTextParams(
                    messages = request.messages,
                    model = request.model,
                    temperature = request.temperature,
                    topP = request.topP,
                    maxOutputTokens = request.maxTokens,
                    tools = activeTools.associateBy { it.function.name },
                    enableWebSearch = sessionForCtx.options.webSearch == true
                )
                val sConfig = com.promenar.nexara.data.remote.StreamConfig(
                    enableWebSearch = sessionForCtx.options.webSearch == true
                )
                unifiedLlmClient.sendStream(stParams, sConfig)
            } else {
                llmProvider.sendPrompt(request)
            }
            _generationStatus.update { GenerationStatus.THINKING }
            
            flow.collect { chunk ->
                if (_generationStatus.value == GenerationStatus.THINKING && (chunk is StreamChunk.TextDelta || chunk is StreamChunk.Thinking)) {
                    _generationStatus.update { GenerationStatus.RECEIVING }
                }
                when (chunk) {
                    is StreamChunk.TextDelta -> {
                        accumulatedContent += chunk.content
                        chunk.reasoning?.let { accumulatedReasoning += it }
                        val sanitized = sanitizeStreamingContent(accumulatedContent, accumulatedToolCalls)
                        if (sanitized.isToolInjection) {
                            accumulatedContent = sanitized.cleanText
                            appendSyntheticExecutionStep(sessionId, assistantMsgId, sanitized.toolCallData)
                        }
                        _streamingContent.update { accumulatedContent }
                        messageManager.updateMessageContent(
                            sessionId, assistantMsgId, accumulatedContent,
                            UpdateMessageOptions(
                                reasoning = accumulatedReasoning.ifBlank { null },
                                tokens = accumulatedTokens
                            )
                        )
                    }
                    is StreamChunk.Thinking -> {
                        accumulatedReasoning += chunk.content
                        messageManager.updateMessageContent(
                            sessionId, assistantMsgId, accumulatedContent,
                            UpdateMessageOptions(reasoning = accumulatedReasoning.ifBlank { null })
                        )
                    }
                    is StreamChunk.ToolCallDelta -> {
                        val existing = accumulatedToolCalls.find { it.id == chunk.id }
                        if (existing != null) {
                            accumulatedToolCalls[accumulatedToolCalls.indexOf(existing)] = existing.copy(
                                name = if (chunk.name.isNotEmpty()) chunk.name else existing.name,
                                arguments = existing.arguments + chunk.arguments
                            )
                        } else {
                            accumulatedToolCalls.add(
                                ToolCall(id = chunk.id, name = chunk.name, arguments = chunk.arguments)
                            )
                        }
                        messageManager.updateMessageContent(
                            sessionId, assistantMsgId, accumulatedContent,
                            UpdateMessageOptions(toolCalls = accumulatedToolCalls.toList())
                        )
                    }
                    is StreamChunk.Usage -> {
                        accumulatedTokens = TokenUsage(
                            input = chunk.usage.input,
                            output = chunk.usage.output,
                            total = chunk.usage.total
                        )
                    }
                    is StreamChunk.Citations -> {
                        val mappedCitations = chunk.citations.map {
                            com.promenar.nexara.data.model.Citation(
                                title = it.title,
                                url = it.url,
                                source = it.source
                            )
                        }
                        messageManager.updateMessageContent(
                            sessionId, assistantMsgId, accumulatedContent,
                            UpdateMessageOptions(citations = mappedCitations)
                        )
                    }
                    is StreamChunk.Error -> {
                        _error.update { chunk.message }
                        messageManager.updateMessageContent(
                            sessionId, assistantMsgId, accumulatedContent,
                            UpdateMessageOptions(
                                isError = true,
                                errorMessage = chunk.message
                            )
                        )
                        // 发现错误立即停止当前流收集，防止并发冲突和后续乱序输出
                        currentCoroutineContext().cancel(
                            kotlinx.coroutines.CancellationException("Stream error received: ${chunk.message}")
                        )
                    }
                    is StreamChunk.ToolCallLifecycle -> {}
                    is StreamChunk.Done -> {}
                }
            }
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                _error.update { "Generation failed: ${e.message}" }
                messageManager.updateMessageContent(
                    sessionId, assistantMsgId, accumulatedContent,
                    UpdateMessageOptions(isError = true, errorMessage = e.message)
                )
            }
            _isGenerating.update { false }
            _generationStatus.update { GenerationStatus.ERROR }
            viewModelScope.launch {
                kotlinx.coroutines.delay(2000)
                _generationStatus.update { GenerationStatus.IDLE }
            }
        }

        // 流式错误或协程取消后，禁止继续执行正常完成路径（避免 ERROR 被 COMPLETED 覆盖）
        if (!currentCoroutineContext().isActive) return

        messageManager.flushMessageUpdates(sessionId, assistantMsgId)
        _streamingContent.update { "" }

        // ── Fallback 解析器：模型可能在 TextDelta 中以 Markdown 代码块形式输出工具调用 JSON ──
        // 部分模型（如 MiniMax-M2.7）不会通过标准 ToolCallDelta 协议下发工具指令，
        // 而是将工具调用以 JSON 代码块形式嵌入普通文本流。此处做后置正则提取兜底。
        val hasCompleteToolCalls = accumulatedToolCalls.isNotEmpty() && accumulatedToolCalls.all {
            it.name.isNotEmpty() && it.arguments.isNotEmpty()
        }
        if (!hasCompleteToolCalls && accumulatedContent.isNotBlank()) {
            val fallbackCalls = extractToolCallsFromText(accumulatedContent)
            if (fallbackCalls.isNotEmpty()) {
                // 将提取到的工具调用追加到累积列表
                accumulatedToolCalls.addAll(fallbackCalls)
                // 从显示内容中移除 JSON 代码块，避免用户在气泡中看到原始 JSON
                accumulatedContent = stripToolCallJsonBlocks(accumulatedContent)
                // 刷新 UI：用清洗后的内容更新消息
                messageManager.updateMessageContent(
                    sessionId, assistantMsgId, accumulatedContent,
                    UpdateMessageOptions(toolCalls = accumulatedToolCalls.toList())
                )
            }
        }

        if (accumulatedToolCalls.isNotEmpty() && currentCoroutineContext().isActive) {
            val executionMode = sessionForCtx.executionMode.ifEmpty { "semi" }
            val pendingIds = determinePendingToolIds(accumulatedToolCalls, executionMode)

            if (pendingIds.isNotEmpty()) {
                messageManager.updateMessageContent(
                    sessionId, assistantMsgId, accumulatedContent,
                    UpdateMessageOptions(pendingApprovalToolIds = pendingIds)
                )

                val firstPendingTc = accumulatedToolCalls.find { it.id in pendingIds }
                approvalManager.setApprovalRequest(sessionId, ApprovalRequest(
                    toolName = firstPendingTc?.name,
                    args = firstPendingTc?.arguments,
                    reason = "Execution mode: $executionMode",
                    type = "tool_approval"
                ))
                approvalManager.setLoopStatus(sessionId, com.promenar.nexara.data.model.LoopStatus.WAITING_FOR_APPROVAL)

                val safeToolCalls = accumulatedToolCalls.filter { it.id !in pendingIds }
                if (safeToolCalls.isNotEmpty()) {
                    toolExecutor.executeTools(sessionId, safeToolCalls, assistantMsgId)
                }

                _isGenerating.update { false }
            } else {
                toolExecutor.executeTools(sessionId, accumulatedToolCalls.toList(), assistantMsgId)

                _isGenerating.update { false }
                if (currentCoroutineContext().isActive) {
                    val newAssistantMsgId = IdGenerator.message("ai")
                    val newAssistantMsg = Message(
                        id = newAssistantMsgId,
                        role = MessageRole.ASSISTANT,
                        content = "",
                        modelId = sessionForCtx.modelId,
                        createdAt = System.currentTimeMillis()
                    )
                    messageManager.addMessage(sessionId, newAssistantMsg)
                    generateMessage(sessionId, "", true, newAssistantMsgId, loopCount = loopCount + 1)
                }
            }
        } else {
            _isGenerating.update { false }
            _generationStatus.update { GenerationStatus.COMPLETED }
            viewModelScope.launch {
                kotlinx.coroutines.delay(1000)
                _generationStatus.update { GenerationStatus.IDLE }
            }
        }

        if (userMsgId != null && currentCoroutineContext().isActive) {
            viewModelScope.launch {
                try {
                    val finalSession = store.getSession(sessionId) ?: return@launch
                    val agent = com.promenar.nexara.data.model.Agent(
                        id = finalSession.agentId,
                        name = finalSession.agentId
                    )
                    val sessionRagOpts = finalSession.ragOptions
                    val isRagEnabled = sessionRagOpts?.enableMemory == true || sessionRagOpts?.enableDocs == true
                    
                    val totalCtxTokens = contextResult.ragUsage?.ragSystem ?: 0
                    
                    val ppParams = com.promenar.nexara.ui.chat.manager.PostProcessorParams(
                        sessionId = sessionId,
                        assistantMsgId = assistantMsgId,
                        userMsgId = userMsgId,
                        userContent = effectiveUserContent,
                        assistantContent = accumulatedContent,
                        agent = agent,
                        session = finalSession,
                        ragEnabled = isRagEnabled,
                        ragUsage = contextResult.ragUsage,
                        accumulatedUsage = accumulatedTokens,
                        totalContextTokens = totalCtxTokens,
                        modelId = finalSession.modelId ?: ""
                    )
                    postProcessor.updateStats(ppParams)

                    // P0 修复: 每轮对话完成后自动存储记忆向量 (addTurnToMemory 之前从未被调用)
                    if (sessionRagOpts?.enableMemory == true
                        && effectiveUserContent.isNotBlank()
                        && accumulatedContent.isNotBlank()
                        && memoryManager != null
                    ) {
                        try {
                            val turnStart = System.currentTimeMillis()
                            memoryManager.addTurnToMemory(
                                sessionId = sessionId,
                                userContent = effectiveUserContent,
                                aiContent = accumulatedContent,
                                userMessageId = userMsgId,
                                assistantMessageId = assistantMsgId
                            )
                            val turnMs = System.currentTimeMillis() - turnStart
                            NexaraLogger.log("[ChatViewModel] addTurnToMemory success: session=$sessionId, time=${turnMs}ms")
                        } catch (e: Exception) {
                            NexaraLogger.logError("[ChatViewModel] addTurnToMemory failed for session=$sessionId", e)
                        }
                    }

                    // Sliding window and archiving logic
                    val windowSize = finalSession.inferenceParams?.activeContextWindow ?: 10
                    if (finalSession.messages.size > windowSize) {
                        val activeMsgs = getSafeActiveWindow(finalSession.messages, windowSize)
                        val activeMsgIds = activeMsgs.map { it.id }.toSet()
                        
                        val overflowMsgs = finalSession.messages.filter { it.id !in activeMsgIds && !it.isArchived }
                        
                        if (overflowMsgs.isNotEmpty()) {
                            // 1. Archive to RAG
                            if (sessionRagOpts?.enableMemory == true) {
                                val archiveTaskId = addPostProcessTask(
                                    type = PostProcessType.ARCHIVE_TO_RAG,
                                    detail = "Archiving ${overflowMsgs.size} messages"
                                )
                                try {
                                    postProcessor.archiveMessagesToRag(
                                        sessionId, overflowMsgs, finalSession.modelId ?: "",
                                        onProgress = { progress, detail ->
                                            updatePostProcessTask(archiveTaskId, progress = progress, detail = detail)
                                        }
                                    )
                                    updatePostProcessTask(archiveTaskId, status = PostProcessStatus.DONE, progress = 1f)
                                    viewModelScope.launch {
                                        kotlinx.coroutines.delay(3000)
                                        removePostProcessTask(archiveTaskId)
                                    }
                                } catch (e: Exception) {
                                    updatePostProcessTask(archiveTaskId, status = PostProcessStatus.ERROR, detail = e.message ?: "Archive failed")
                                }
                            }
                            
                            // 2. Mark as archived in DB
                            overflowMsgs.forEach { msg ->
                                messageManager.updateMessage(sessionId, msg.id, msg.copy(isArchived = true))
                            }
                            
                            // 3. Auto-summary trigger
                            val threshold = finalSession.inferenceParams?.autoSummaryThreshold ?: 0.8
                            val totalTokens = accumulatedTokens.total
                            val maxTokens = com.promenar.nexara.data.model.findModelSpec(
                                finalSession.modelId ?: ""
                            )?.contextLength ?: 128000
                            
                            if (totalTokens > maxTokens * threshold) {
                                val summaryTaskId = addPostProcessTask(
                                    type = PostProcessType.AUTO_SUMMARY,
                                    detail = "Summarizing conversation"
                                )
                                val settingsPrefs = application.getSharedPreferences("nexara_settings", 0)
                                val summaryModelId = settingsPrefs.getString("preset_summary_model", "")
                                
                                try {
                                    val newSummary = summaryManager.summarize(
                                        oldSummary = finalSession.summary,
                                        overflowMessages = overflowMsgs,
                                        summaryModelId = summaryModelId,
                                        currentModelId = finalSession.modelId ?: "",
                                        onProgress = { detail ->
                                            updatePostProcessTask(summaryTaskId, detail = detail)
                                        }
                                    )
                                    
                                    if (newSummary != finalSession.summary) {
                                        sessionManager.updateSession(sessionId, mapOf("summary" to newSummary))
                                    }
                                    updatePostProcessTask(summaryTaskId, status = PostProcessStatus.DONE, progress = 1f)
                                    viewModelScope.launch {
                                        kotlinx.coroutines.delay(3000)
                                        removePostProcessTask(summaryTaskId)
                                    }
                                } catch (e: Exception) {
                                    updatePostProcessTask(summaryTaskId, status = PostProcessStatus.ERROR, detail = e.message ?: "Summary failed")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        } finally {
            // 保证 isGenerating 在任何退出路径下都被重置
            if (_isGenerating.value) {
                _isGenerating.update { false }
            }
        }
    }

    fun loadSession(sessionId: String) {
        _ragPhases.update { emptyList() }
        val existing = store.getSession(sessionId)
        if (existing != null) {
            _currentSessionId.update { sessionId }
            updateAgentName(existing.agentId)
            // 同步当前会话的 ragOptions 到缓存
            existing.ragOptions?.let { _currentRagOptions.value = it }
            // P0 持久化修复: 恢复历史检索指示器
            val lastMsg = existing.messages.lastOrNull { it.role == MessageRole.ASSISTANT && !it.ragReferences.isNullOrEmpty() }
            if (lastMsg?.ragReferences.isNullOrEmpty() == false) {
                _ragPhases.update {
                    listOf(
                        RagPhase("retrieved", "已检索", PhaseStatus.DONE, 100,
                            "${lastMsg!!.ragReferences!!.size} 个来源")
                    )
                }
            }
            // 恢复未发送的草稿
            if (!existing.draft.isNullOrBlank()) {
                _inputText.update { existing.draft!! }
            }
            return
        }

        viewModelScope.launch {
            _isLoading.update { true }
            try {
                val session = sessionRepository.getById(sessionId)
                if (session != null) {
                    val messages = messageRepository.getBySession(sessionId)
                    var hydrated = session.copy(messages = messages)
                    if (hydrated.ragOptions == null) {
                        val defaultOptions = getDefaultRagOptions()
                        hydrated = hydrated.copy(ragOptions = defaultOptions)
                        sessionManager.updateSession(sessionId, mapOf("ragOptions" to defaultOptions))
                    }
                    store.update { state ->
                        if (state.sessions.any { it.id == sessionId }) state
                        else state.copy(sessions = state.sessions + hydrated)
                    }
                    _currentSessionId.update { sessionId }
                    updateAgentName(hydrated.agentId)
                    // 同步当前会话的 ragOptions 到缓存
                    hydrated.ragOptions?.let { _currentRagOptions.value = it }
                // P0 持久化修复: 若会话中已有检索结果，恢复指示器为历史完成态
                val lastAssistantMsg = hydrated.messages.lastOrNull { it.role == MessageRole.ASSISTANT && !it.ragReferences.isNullOrEmpty() }
                if (lastAssistantMsg?.ragReferences.isNullOrEmpty() == false) {
                    _ragPhases.update {
                        listOf(
                            RagPhase("retrieved", "已检索", PhaseStatus.DONE, 100,
                                "${lastAssistantMsg!!.ragReferences!!.size} 个来源")
                        )
                    }
                }
                // 从 DB 恢复未发送的草稿
                    if (!session.draft.isNullOrBlank()) {
                        _inputText.update { session.draft!! }
                    }
                } else {
                    _error.update { "Session not found: $sessionId" }
                }
            } catch (e: Exception) {
                _error.update { "Failed to load session: ${e.message}" }
            } finally {
                _isLoading.update { false }
            }
        }
    }

    fun createNewSession(agentId: String) {
        viewModelScope.launch {
            val sessionId = IdGenerator.session()
            val workspacePath = File((application as NexaraApplication).filesDir, "workspaces/$sessionId").apply {
                if (!exists()) mkdirs()
            }.absolutePath
            
            val defaultOptions = getDefaultRagOptions()
            val session = Session(
                id = sessionId,
                agentId = agentId,
                workspacePath = workspacePath,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                ragOptions = defaultOptions
            )
            sessionManager.addSession(session)
            _currentSessionId.update { sessionId }
            updateAgentName(agentId)
        }
    }

    private fun updateAgentName(agentId: String?) {
        if (agentId == null) {
            _agentName.value = ""
            return
        }
        viewModelScope.launch {
            val agent = agentRepository.getById(agentId)
            _agentName.value = configResolver.resolveName(agent)
        }
    }

    fun stopGeneration() {
        cancelActiveGeneration()
        val sessionId = _currentSessionId.value
        if (sessionId != null) {
            approvalManager.setLoopStatus(sessionId, com.promenar.nexara.data.model.LoopStatus.PAUSED)
        }
    }

    private fun cancelActiveGeneration() {
        generationJob?.cancel()
        generationJob = null
        llmProvider.cancel()
        _isGenerating.update { false }
        _streamingContent.update { "" }
    }

    fun retryLastMessage() {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return

        val lastUserMsg = session.messages.lastOrNull { it.role == MessageRole.USER }
        if (lastUserMsg == null) return

        val lastAssistantMsg = session.messages.lastOrNull { it.role == MessageRole.ASSISTANT }

        if (lastAssistantMsg != null) {
            viewModelScope.launch {
                messageManager.deleteMessage(sessionId, lastAssistantMsg.id)
            }
        }

        sendMessage(lastUserMsg.content)
    }

    fun approveRequest(intervention: String? = null) {
        val sessionId = _currentSessionId.value ?: return
        cancelActiveGeneration()
        generationJob = viewModelScope.launch {
            approvalManager.resumeGeneration(sessionId, approved = true, intervention = intervention)
        }
    }

    fun rejectRequest() {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            approvalManager.resumeGeneration(sessionId, approved = false)
        }
    }

    fun clearError() {
        _error.update { null }
    }

    fun regenerateLastMessage() {
        retryLastMessage()
    }

    fun editAndResend(messageId: String, newContent: String) {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return

        val msgIndex = session.messages.indexOfFirst { it.id == messageId }
        if (msgIndex < 0) return

        cancelActiveGeneration()
        generationJob = viewModelScope.launch {
            messageManager.updateMessageContent(sessionId, messageId, newContent, UpdateMessageOptions())

            val toRemove = session.messages.drop(msgIndex + 1).map { it.id }
            toRemove.forEach { messageManager.deleteMessage(sessionId, it) }

            _inputText.update { "" }
            _error.update { null }

            val assistantMsgId = IdGenerator.message("ai")
            val updatedSession = store.getSession(sessionId) ?: return@launch
            val assistantMessage = Message(
                id = assistantMsgId,
                role = MessageRole.ASSISTANT,
                content = "",
                modelId = updatedSession.modelId,
                createdAt = System.currentTimeMillis()
            )
            messageManager.addMessage(sessionId, assistantMessage)
            generateMessage(sessionId, "", false, assistantMsgId, messageId, newContent)
        }
    }

    private val _currentRagOptions = MutableStateFlow(com.promenar.nexara.data.model.RagOptions())
    val currentRagOptions: StateFlow<com.promenar.nexara.data.model.RagOptions> = _currentRagOptions

    fun updateRagOptions(options: com.promenar.nexara.data.model.RagOptions) {
        val sessionId = _currentSessionId.value ?: return
        _currentRagOptions.value = options  // 立即缓存，绕过 store 异步延迟
        NexaraLogger.log("[ChatViewModel] updateRagOptions: enableMemory=${options.enableMemory}, enableDocs=${options.enableDocs}, isGlobal=${options.isGlobal}, enableRerank=${options.enableRerank}, enableKG=${options.enableKnowledgeGraph}")
        viewModelScope.launch {
            sessionManager.updateSession(sessionId, mapOf("ragOptions" to options))
        }
    }

    fun summarizeHistory() {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return
        
        viewModelScope.launch {
            try {
                _isGenerating.update { true }
                val messages = session.messages.filter { !it.isArchived }
                val settingsPrefs = application.getSharedPreferences("nexara_settings", 0)
                val summaryModelId = settingsPrefs.getString("preset_summary_model", "")
                
                val newSummary = summaryManager.summarize(
                    oldSummary = session.summary,
                    overflowMessages = messages,
                    summaryModelId = summaryModelId,
                    currentModelId = session.modelId ?: ""
                )
                
                sessionManager.updateSession(sessionId, mapOf("summary" to newSummary))
                
                // Mark these as archived
                messages.forEach { msg ->
                    messageManager.updateMessage(sessionId, msg.id, msg.copy(isArchived = true))
                }
            } catch (e: Exception) {
                _error.update { "Manual summary failed: ${e.message}" }
            } finally {
                _isGenerating.update { false }
            }
        }
    }

    fun compressContext() {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return

        viewModelScope.launch {
            try {
                _compressionState.update { CompressionState(isCompressing = true, progress = 0.1f, detail = application.getString(R.string.chat_summary_card_progress_preparing)) }
                _isGenerating.update { true }

                val messages = session.messages.filter { !it.isArchived }
                if (messages.isEmpty()) {
                    _compressionState.update { CompressionState(isCompressing = false, detail = "No messages to compress") }
                    return@launch
                }

                val settingsPrefs = application.getSharedPreferences("nexara_settings", 0)
                val summaryModelId = settingsPrefs.getString("preset_summary_model", "")

                _compressionState.update { it.copy(progress = 0.3f, detail = application.getString(R.string.chat_summary_card_progress_calling)) }

                val newSummary = summaryManager.summarize(
                    oldSummary = session.summary,
                    overflowMessages = messages,
                    summaryModelId = summaryModelId,
                    currentModelId = session.modelId ?: "",
                    onProgress = { detail ->
                        _compressionState.update { it.copy(progress = (it.progress + 0.15f).coerceAtMost(0.9f), detail = detail) }
                    }
                )

                _compressionState.update { it.copy(progress = 0.95f, detail = application.getString(R.string.chat_summary_card_progress_done)) }

                sessionManager.updateSession(sessionId, mapOf("summary" to newSummary))

                messages.forEach { msg ->
                    messageManager.updateMessage(sessionId, msg.id, msg.copy(isArchived = true))
                }

                _compressionState.update { CompressionState(isCompressing = false, progress = 1f, result = newSummary) }
            } catch (e: Exception) {
                _compressionState.update { CompressionState(isCompressing = false, detail = e.message ?: "Compression failed") }
                _error.update { "Context compression failed: ${e.message}" }
            } finally {
                _isGenerating.update { false }
            }
        }
    }

    fun dismissCompressionResult() {
        _compressionState.update { CompressionState() }
    }

    fun resendMessage(messageId: String) {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return
        val message = session.messages.find { it.id == messageId } ?: return
        
        if (message.role == MessageRole.USER) {
            // Re-trigger from user message
            regenerateMessage(messageId)
        } else {
            // Find preceding user message
            val userMsg = session.messages.takeWhile { it.id != messageId }.lastOrNull { it.role == MessageRole.USER }
            if (userMsg != null) {
                regenerateMessage(userMsg.id)
            } else {
                // Just regenerate from current state
                cancelActiveGeneration()
                generationJob = viewModelScope.launch {
                    messageManager.deleteMessage(sessionId, messageId) { stopGeneration() }
                    generateMessage(sessionId, "", false)
                }
            }
        }
    }

    fun toggleTool(toolName: String, enabled: Boolean) {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return
        val options = session.options ?: SessionOptions()
        val nextOptions = when (toolName) {
            "timeInjection" -> options.copy(enableTimeInjection = enabled)
            "toolsEnabled" -> options.copy(toolsEnabled = enabled)
            "economyMode" -> options.copy(economyMode = enabled)
            else -> options
        }
        viewModelScope.launch {
            sessionManager.updateSessionOptions(sessionId, nextOptions)
        }
    }

    fun updateFontSize(size: Int) {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return
        val options = session.options ?: SessionOptions()
        viewModelScope.launch {
            sessionManager.updateSessionOptions(sessionId, options.copy(fontSize = size))
        }
    }

    fun updateFontSizeLocally(size: Int) {
        val sessionId = _currentSessionId.value ?: return
        store.updateSession(sessionId) { s ->
            val options = s.options ?: SessionOptions()
            s.copy(options = options.copy(fontSize = size))
        }
    }

    fun updateSessionTitle(title: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionManager.updateSessionTitle(sessionId, title)
        }
    }

    fun updateInferenceParams(params: InferenceParams) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionManager.updateSessionInferenceParams(sessionId, params)
        }
    }


    fun updateModelId(modelId: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionManager.updateSessionModel(sessionId, modelId)
        }
    }

    fun updateCustomPrompt(prompt: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionManager.updateSessionPrompt(sessionId, prompt)
        }
    }

    private fun buildToolList(session: Session): List<com.promenar.nexara.data.remote.protocol.ProtocolTool> {
        if (!session.options.toolsEnabled) return emptyList()
        
        val prefs = application.getSharedPreferences("nexara_settings", 0)
        val enabledSkills = prefs.getStringSet("enabled_skills", null)
        
        if (enabledSkills.isNullOrEmpty()) return emptyList()
        
        // Tool availability is controlled entirely by the skill-level toggle in Settings.
        // No session-level web search switch — avoid dual-control confusion.
        val allowedIds = enabledSkills.toMutableList()
        
        return skillRegistry?.getAllTools(allowedIds) ?: emptyList()
    }

    private fun buildProtocolMessages(
        session: Session,
        systemPrompt: String
    ): List<ProtocolMessage> {
        val messages = mutableListOf<ProtocolMessage>()

        if (systemPrompt.isNotBlank()) {
            messages.add(ProtocolMessage(role = "system", content = systemPrompt))
        }

        val activeWindowSize = session.inferenceParams?.activeContextWindow ?: 10
        val activeMessages = getSafeActiveWindow(session.messages, activeWindowSize)

        for (msg in activeMessages) {
            val protocolMsg = when (msg.role) {
                MessageRole.USER -> {
                    val imageInputs = msg.userImages?.map { dataUrl ->
                        val base64Prefix = "base64,"
                        val base64Idx = dataUrl.indexOf(base64Prefix)
                        val mimeEnd = dataUrl.indexOf(";")
                        val mime = if (mimeEnd > 5) dataUrl.substring(5, mimeEnd) else "image/jpeg"
                        val base64Data = if (base64Idx >= 0) dataUrl.substring(base64Idx + base64Prefix.length) else ""
                        ImageInput(url = dataUrl, base64 = base64Data, mimeType = mime)
                    }
                    ProtocolMessage(role = "user", content = msg.content, imageUrls = imageInputs)
                }
                MessageRole.ASSISTANT -> ProtocolMessage(
                    role = "assistant",
                    content = msg.content,
                    reasoning = msg.reasoning,
                    toolCalls = msg.toolCalls?.map {
                        com.promenar.nexara.data.remote.protocol.ProtocolToolCall(
                            id = it.id,
                            name = it.name,
                            arguments = it.arguments
                        )
                    }
                )
                MessageRole.SYSTEM -> ProtocolMessage(role = "system", content = msg.content)
                MessageRole.TOOL -> ProtocolMessage(
                    role = "tool",
                    content = msg.content,
                    toolCallId = msg.toolCallId,
                    name = msg.name
                )
            }
            messages.add(protocolMsg)
        }

        return messages
    }

    private fun getSafeActiveWindow(messages: List<Message>, windowSize: Int): List<Message> {
        if (messages.size <= windowSize) return messages
        
        var startIdx = messages.size - windowSize
        
        // Ensure we don't break tool pairs
        // If we start at a TOOL message, we must include its parent ASSISTANT message
        while (startIdx > 0 && messages[startIdx].role == MessageRole.TOOL) {
            startIdx--
        }
        
        // If we start AFTER an ASSISTANT message that has tool calls, we must check if any of its tools are later
        // But tool results always come after. So the main risk is starting at a TOOL message.
        
        return messages.drop(startIdx)
    }

    private val lastDeletedMessages = mutableListOf<Pair<String, Message>>()

    fun undoLastDeletion() {
        if (lastDeletedMessages.isEmpty()) return
        viewModelScope.launch {
            // Sort by createdAt to ensure correct order when re-inserting
            val sorted = lastDeletedMessages.sortedBy { it.second.createdAt }
            sorted.forEach { (sid, msg) ->
                messageManager.addMessage(sid, msg)
            }
            lastDeletedMessages.clear()
        }
    }

    private suspend fun backupAndTruncate(sessionId: String, timestamp: Long) {
        val session = store.getSession(sessionId) ?: return
        val toDelete = session.messages.filter { it.createdAt >= timestamp }
        if (toDelete.isNotEmpty()) {
            lastDeletedMessages.clear()
            toDelete.forEach { lastDeletedMessages.add(sessionId to it) }
            messageManager.deleteMessagesAfter(sessionId, timestamp) { stopGeneration() }
        }
    }

    fun saveCurrentDraft() {
        val sessionId = _currentSessionId.value ?: return
        val text = _inputText.value
        viewModelScope.launch {
            sessionManager.updateSessionDraft(sessionId, text.ifBlank { null })
        }
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        llmProvider.cancel()
    }

    fun deleteMessage(messageId: String) {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return
        val message = session.messages.find { it.id == messageId } ?: return
        
        viewModelScope.launch {
            lastDeletedMessages.clear()
            lastDeletedMessages.add(sessionId to message)
            messageManager.deleteMessage(sessionId, messageId) { stopGeneration() }
        }
    }

    fun editMessage(messageId: String, newContent: String) {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return
        val message = session.messages.find { it.id == messageId } ?: return
        
        cancelActiveGeneration()
        generationJob = viewModelScope.launch {
            backupAndTruncate(sessionId, message.createdAt + 1)
            messageManager.updateMessageContent(sessionId, messageId, newContent)
            generateMessage(sessionId, "", false)
        }
    }

    fun updateMessageContentOnly(messageId: String, newContent: String) {
        val sessionId = _currentSessionId.value ?: return
        messageManager.updateMessageContent(sessionId, messageId, newContent)
    }

    fun regenerateMessage(messageId: String) {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return
        val message = session.messages.find { it.id == messageId } ?: return
        
        cancelActiveGeneration()
        generationJob = viewModelScope.launch {
            // Delete all messages strictly after this one with backup
            backupAndTruncate(sessionId, message.createdAt + 1)
            
            // Create a new assistant message
            val assistantMsgId = IdGenerator.message("ai")
            val assistantMessage = Message(
                id = assistantMsgId,
                role = MessageRole.ASSISTANT,
                content = "",
                modelId = session.modelId,
                createdAt = System.currentTimeMillis()
            )
            messageManager.addMessage(sessionId, assistantMessage)
            
            // Trigger regeneration
            generateMessage(sessionId, "", false, assistantMsgId, messageId, message.content)
        }
    }

    fun clearHistory() {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            messageManager.clearMessages(sessionId)
        }
    }

    fun renameSession(newName: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionManager.updateSessionTitle(sessionId, newName)
        }
    }

    fun deleteSession() {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionManager.deleteSession(sessionId)
        }
    }

    suspend fun exportSession(
        sessionId: String,
        format: ExportSessionUseCase.Format
    ): ExportSessionUseCase.ExportResult {
        val useCase = exportSessionUseCase ?: throw IllegalStateException("ExportSessionUseCase not available")
        return useCase.export(sessionId, format)
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as NexaraApplication
                    return ChatViewModel(
                        application = application,
                        sessionRepository = app.sessionRepository,
                        messageRepository = app.messageRepository,
                        agentRepository = app.agentRepository,
                        llmProvider = app.llmProvider,
                        unifiedLlmClient = app.unifiedLlmClient,
                        configResolver = app.configResolver,
                        embeddingClient = app.embeddingClient,
                        vectorStore = app.vectorStore,
                        textSplitter = app.textSplitter,
                        memoryManager = app.memoryManager,
                        kgProvider = app.kgProvider,
                        skillRegistry = app.skillRegistry,
                        exportSessionUseCase = ExportSessionUseCase(
                            app.messageRepository as com.promenar.nexara.domain.repository.IMessageRepository,
                            app.sessionRepository as com.promenar.nexara.domain.repository.ISessionRepository
                        )
                    ) as T
                }
            }
    }

    private fun updateTokenIndicator(session: Session) {
        viewModelScope.launch {
            val agent = session.agentId?.let { agentRepository.getById(it) }
            
            // Reconstruct system prompt parts for accurate estimation (matching ContextBuilder)
            val agentPrompt = agent?.systemPrompt ?: ""
            val sessionPrompt = session.customPrompt ?: ""
            
            // Metadata overhead (Time, Tools instructions, Active Task)
            var metadataText = ""
            if (session.options.enableTimeInjection) {
                metadataText += "[System Time: 2024-01-01 12:00:00 Monday]\n\n"
            }
            if (session.options.toolsEnabled) {
                metadataText += "[You have access to function calling tools. Use them when needed to provide accurate and up-to-date responses.]\n\n"
            }
            if (session.activeTask != null && session.activeTask.status == "in-progress") {
                metadataText += "## Active Task\n- **Current Task**: \"Title\"\n- **Immediate Goal**: Goal\n\n"
            }

            val fullSystemPrompt = buildString {
                append(metadataText)
                if (agentPrompt.isNotBlank()) appendLine(agentPrompt)
                if (sessionPrompt.isNotBlank()) {
                    appendLine()
                    appendLine(sessionPrompt)
                }
            }
            
            val summary = session.summary ?: ""
            val activeMsgs = getSafeActiveWindow(session.messages, session.inferenceParams?.activeContextWindow ?: 10)
            
            val systemTokens = PostProcessor.estimateTokens(fullSystemPrompt)
            val summaryTokens = PostProcessor.estimateTokens(summary)
            val activeTokens = activeMsgs.sumOf { PostProcessor.estimateTokens(it.content) }
            
            // Fetch real RAG tokens from last stats if available
            val ragTokens = session.stats?.billing?.ragSystem?.count ?: 0
            
            val used = systemTokens + summaryTokens + activeTokens + ragTokens
            
            // Robust way to get context length: check local settings first, then fallback to model spec
            val modelId = session.modelId ?: ""
            val prefs = application.getSharedPreferences("nexara_settings", 0)
            val savedContext = prefs.getInt("model_info_${modelId}_context", 0)
            
            val max = if (savedContext > 0) savedContext 
                     else findModelSpec(modelId)?.contextLength ?: 128000
            
            _tokenIndicatorState.update { 
                it.copy(
                    used = used,
                    max = max,
                    systemTokens = systemTokens,
                    summaryTokens = summaryTokens,
                    activeTokens = activeTokens,
                    ragTokens = ragTokens
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Fallback 解析器：从文本中提取工具调用 JSON
    // ─────────────────────────────────────────────────────────────

    /**
     * 从 LLM 输出的纯文本中提取内嵌的工具调用 JSON。
     *
     * 部分模型（如 MiniMax-M2.7）不会通过标准 ToolCallDelta 协议下发工具指令，
     * 而是在 TextDelta 中以 Markdown 代码块形式输出 JSON。本方法做后置正则兜底。
     *
     * 支持的格式：
     *   1. ```json { "name": "search_searxng", "arguments": {...} } ```
     *   2. ```{ "function": { "name": "...", "arguments": "..." } } ```
     *   3. 裸 JSON 对象含 tool_name/tool/function 字段
     */
    private fun extractToolCallsFromText(content: String): List<ToolCall> {
        val results = mutableListOf<ToolCall>()

        val dsmlParser = com.promenar.nexara.data.remote.parser.DsmlStreamParser()
        val outputText = StringBuilder()
        val dsmlCalls = dsmlParser.process(content, outputText)
        dsmlParser.flush(outputText)
        if (dsmlCalls.isNotEmpty()) {
            dsmlCalls.mapTo(results) { dc ->
                val argsMap = buildJsonObject { dc.args.forEach { (k, v) ->
                    put(k, when (v) {
                        is String -> JsonPrimitive(v)
                        is Number -> JsonPrimitive(v)
                        is Boolean -> JsonPrimitive(v)
                        is JsonElement -> v
                        else -> JsonPrimitive(v.toString())
                    })
                }}
                ToolCall(
                    id = "dsml_${System.currentTimeMillis()}_${results.size}",
                    name = dc.toolName,
                    arguments = argsMap.toString()
                )
            }
            return results
        }

        // [DeepSeek 审计优化] xmlToolCallRegex 带有捕获组 (.*?)，用于在 Fallback 时精准提取文本段。
        // 它与文件底部的 XML_TOOL_PATTERN（无捕获组，用于整体匹配剔除）分工不同。
        val xmlToolCallRegex = Regex(
            """<(?:tool_call|function_call)[^>]*>(.*?)</(?:tool_call|function_call)>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        val codeBlockRegex = Regex(
            """```(?:json)?\s*\n(.*?)\n\s*```""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        val candidates = mutableListOf<String>()

        // 优先 1：XML 标签提取
        xmlToolCallRegex.findAll(content).forEach { candidates.add(it.groupValues[1]) }

        // 优先 2：Markdown 代码块提取
        codeBlockRegex.findAll(content).forEach { candidates.add(it.groupValues[1]) }

        // 优先 3：终极兜底，用数学精确的大括号匹配算法寻找嵌套裸 JSON
        if (candidates.isEmpty()) {
            val segments = scanBalancedJsonSegments(content)
            segments.forEach { candidates.add(it.content) }
        }

        for (candidate in candidates) {
            val trimmed = candidate.trim()
            try {
                val element = Json.parseToJsonElement(trimmed)
                val toolCall = parseToolCallFromJson(element, results.size)
                if (toolCall != null) {
                    results.add(toolCall)
                }
            } catch (_: Exception) {
                // [DeepSeek 审计优化] 如果外层解析失败，尝试深度检索一次（兼容首尾存在杂质文本的情况）
                val segments = scanBalancedJsonSegments(trimmed)
                for (segment in segments) {
                    try {
                        val inner = Json.parseToJsonElement(segment.content)
                        val tc = parseToolCallFromJson(inner, results.size)
                        if (tc != null && results.none { it.name == tc.name }) {
                            results.add(tc)
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        return results
    }

    /**
     * 从 JsonElement 解析为 ToolCall，支持多种字段命名约定：
     *  - name / function.name / tool / tool_name
     *  - arguments / parameters / input / function.arguments
     */
    private fun parseToolCallFromJson(element: JsonElement, index: Int): ToolCall? {
        if (element !is JsonObject) return null

        // ── 提取工具名称 ──
        val name: String = element["name"]?.jsonPrimitive?.content
            ?: element["tool"]?.jsonPrimitive?.content
            ?: element["tool_name"]?.jsonPrimitive?.content
            ?: element["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: return null

        // ── 提取参数 ──
        val rawArgs: Any? = element["arguments"]
            ?: element["parameters"]
            ?: element["input"]
            ?: element["args"]
            ?: element["function"]?.jsonObject?.get("arguments")

        val arguments: String = when (rawArgs) {
            is JsonObject -> rawArgs.toString()
            is JsonPrimitive -> rawArgs.content
            is String -> rawArgs
            else -> "{}"
        }

        val id = "fallback_${System.currentTimeMillis()}_${name.hashCode()}_$index"
        return ToolCall(id = id, name = name, arguments = arguments)
    }

    /**
     * 从内容中移除 JSON 工具调用代码块，避免用户看到原始 JSON 指令。
     * 处理两种场景：
     *   1. Markdown 代码块包裹的 JSON（```json ... ```）
     *   2. 裸 JSON 对象（以 { 开头含 tool/name/function 字段的行）
     */
    private val XML_TOOL_PATTERN = Regex(
        """<(?:tool_call|function_call|function_name)[^>]*/?>[\s\S]*?</(?:tool_call|function_call)>""",
        RegexOption.IGNORE_CASE
    )

    private val TOOL_RESULT_SEPARATOR_PATTERN = Regex(
        """---\s*(?:工具|tool|search)?\s*(?:调用|执行)?\s*结果\s*[：:]\s*[\s\S]*?(?=\n\n|\n?$)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    private fun stripToolCallJsonBlocks(content: String): String {
        var result = content

        // 1. 彻底剔除 XML 格式标签及其内部内容
        result = result.replace(XML_TOOL_PATTERN, "")

        // 2. 彻底剔除 Markdown 代码块格式工具 JSON
        val codeBlockJsonRegex = Regex(
            """```(?:json)?\s*\n(?:[\s\S]*?"(?:name|function|tool|tool_name)"[\s\S]*?)\n\s*```""",
            setOf(RegexOption.MULTILINE)
        )
        result = result.replace(codeBlockJsonRegex) { "" }

        // 3. 利用大括号配对扫描器，彻底定位并剔除多行或嵌套裸 JSON 段
        var index = 0
        while (index < result.length) {
            val segments = scanBalancedJsonSegments(result, index)
            if (segments.isEmpty()) break

            val firstSegment = segments.first()
            val openBraceIdx = firstSegment.start
            val closeBraceIdx = firstSegment.end
            val possibleJson = firstSegment.content

            var didRemove = false
            try {
                val element = Json.parseToJsonElement(possibleJson) as? JsonObject
                val name = element?.get("name")?.jsonPrimitive?.content
                    ?: element?.get("tool")?.jsonPrimitive?.content
                    ?: element?.get("tool_name")?.jsonPrimitive?.content
                    ?: element?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content

                // [DeepSeek 审计 P0 修复] 使用 getSkill O(1) 安全地检查该工具是否已被合法注册与启用，杜绝编译死锁
                val isRegistered = name?.let { skillRegistry?.getSkill(it) != null } ?: false

                if (isRegistered) {
                    // [DeepSeek 审计 P1 优化] 剪裁掉这部分 JSON 文本段。由于字符串已被物理缩短，
                    // 原 closeBraceIdx 之后的内容在新字符串中向前平移到了原 openBraceIdx 位置。
                    // 故我们将 index 设为 openBraceIdx 重新在此处做下一轮扫描。
                    result = result.substring(0, openBraceIdx) + result.substring(closeBraceIdx + 1)
                    index = openBraceIdx
                    didRemove = true
                }
            } catch (_: Exception) {}

            if (!didRemove) {
                index = closeBraceIdx + 1
            }
        }

        // 4. 清理工具结果指示符与多余空行
        result = result.replace(TOOL_RESULT_SEPARATOR_PATTERN, "")
        result = result.replace(Regex("\n{3,}"), "\n\n").trim()
        
        return result
    }

    private val highRiskToolNames = setOf(
        "write_file", "exec_js", "generate_image", "create_tool"
    )

    private fun determinePendingToolIds(
        toolCalls: List<ToolCall>,
        executionMode: String
    ): List<String> {
        return when (executionMode) {
            "auto" -> emptyList()
            "manual" -> toolCalls.map { it.id }
            else -> toolCalls
                .filter { it.name in highRiskToolNames }
                .map { it.id }
        }
    }

    private fun getDefaultRagOptions(): com.promenar.nexara.data.model.RagOptions {
        val ragPrefs = application.getSharedPreferences("rag_settings", android.content.Context.MODE_PRIVATE)
        val persistence = com.promenar.nexara.domain.usecase.RagConfigPersistence(ragPrefs)
        val fullConfig = persistence.loadFullConfig()
        return com.promenar.nexara.data.model.RagOptions(
            enableMemory = fullConfig.enableMemory,
            enableDocs = fullConfig.enableDocs,
            enableRerank = fullConfig.enableRerank,
            enableKnowledgeGraph = fullConfig.enableKnowledgeGraph,
            isGlobal = true
        )
    }

    private data class SanitizedStreamingContent(
        val cleanText: String,
        val isToolInjection: Boolean,
        val toolCallData: List<ExecutionStep> = emptyList()
    )

    private fun sanitizeStreamingContent(
        content: String,
        toolCalls: List<ToolCall>
    ): SanitizedStreamingContent {
        if (toolCalls.isNotEmpty()) return SanitizedStreamingContent(content, false)

        val separatorIdx = content.indexOf("---")
        if (separatorIdx < 0) return SanitizedStreamingContent(content, false)

        val beforeSeparator = content.substring(0, separatorIdx)
        val afterSeparator = content.substring(separatorIdx)

        val isToolResult = afterSeparator.contains("结果") || afterSeparator.contains("result", ignoreCase = true)
        if (!isToolResult) return SanitizedStreamingContent(content, false)

        return SanitizedStreamingContent(
            cleanText = beforeSeparator.trimEnd(),
            isToolInjection = true,
            toolCallData = listOf(
                ExecutionStep(
                    id = "stream-sniff-${System.currentTimeMillis()}",
                    type = "tool_result",
                    content = afterSeparator.take(500)
                )
            )
        )
    }

    private suspend fun appendSyntheticExecutionStep(
        sessionId: String,
        targetMsgId: String,
        newSteps: List<ExecutionStep>
    ) {
        val currentSession = store.getSession(sessionId) ?: return
        val currentMsg = currentSession.messages.find { it.id == targetMsgId } ?: return
        val currentSteps = currentMsg.executionSteps ?: emptyList()
        val updatedSteps = currentSteps + newSteps
        messageManager.updateMessageContent(
            sessionId, targetMsgId, currentMsg.content,
            UpdateMessageOptions(executionSteps = updatedSteps)
        )
    }

    private data class JsonSegment(val start: Int, val end: Int, val content: String)

    /**
     * [DeepSeek 审计优化] 提取公共的大括号配对扫描器，消除重复代码。
     * 在文本中扫描配对大括号包围的 JSON 段，支持任意深度的对象/数组嵌套，且跳过字面量及转义大括号。
     * @return 匹配到的 JsonSegment 列表（含首尾索引对与 JSON 串），已通过 triggerKeywords 过滤
     */
    private fun scanBalancedJsonSegments(
        text: String,
        startIndex: Int = 0,
        triggerKeywords: List<String> = listOf("\"name\"", "\"tool\"", "\"tool_name\"", "\"function\"")
    ): List<JsonSegment> {
        val segments = mutableListOf<JsonSegment>()
        var index = startIndex
        while (index < text.length) {
            val openBraceIdx = text.indexOf('{', index)
            if (openBraceIdx == -1) break
            
            val closeBraceIdx = findMatchingCloseBrace(text, openBraceIdx)
            
            if (closeBraceIdx != -1) {
                val possibleJson = text.substring(openBraceIdx, closeBraceIdx + 1)
                if (triggerKeywords.any { possibleJson.contains(it) }) {
                    segments.add(JsonSegment(openBraceIdx, closeBraceIdx, possibleJson))
                }
                index = closeBraceIdx + 1
            } else {
                index = openBraceIdx + 1
            }
        }
        return segments
    }

    /**
     * 精确匹配闭合大括号。能够识别双引号作用域，并忽略其内部的任何大括号。
     */
    private fun findMatchingCloseBrace(text: String, startAt: Int): Int {
        var braceCount = 0
        var inQuote = false
        var escaped = false
        for (i in startAt until text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '"') {
                inQuote = !inQuote
                continue
            }
            if (!inQuote) {
                if (c == '{') braceCount++
                else if (c == '}') {
                    braceCount--
                    if (braceCount == 0) return i
                }
            }
        }
        return -1
    }
}

