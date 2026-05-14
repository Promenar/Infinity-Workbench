package com.promenar.nexara.ui.chat

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.data.model.InferenceParams
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.SessionOptions
import com.promenar.nexara.data.model.TokenUsage
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.UpdateMessageOptions
import com.promenar.nexara.data.model.RagMetadata
import com.promenar.nexara.data.model.RagProgress
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
import com.promenar.nexara.data.remote.protocol.StreamChunk
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        kgProvider = kgProvider
    )
    private val toolExecutor = ToolExecutor(store, messageManager, skillRegistry)
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

    fun sendMessage(text: String) {
        val sessionId = _currentSessionId.value
        if (sessionId == null || text.isBlank()) return

        val session = store.getSession(sessionId) ?: return
        val userMsgId = IdGenerator.message("user")
        val assistantMsgId = IdGenerator.message("ai")

        _inputText.update { "" }
        _error.update { null }
        // 清空 DB 中的草稿
        viewModelScope.launch { sessionManager.updateSessionDraft(sessionId, null) }

        cancelActiveGeneration()
        generationJob = viewModelScope.launch {
            val userMessage = Message(
                id = userMsgId,
                role = MessageRole.USER,
                content = text,
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
        _error.update { null } // 每轮新生成强制清除上一轮的残留错误

        val agent = agentRepository.getById(sessionForCtx.agentId)
        val agentConfig = configResolver.resolve(agent)

        val contextParams = ContextBuilderParams(
            sessionId = sessionId,
            content = effectiveUserContent,
            assistantMsgId = assistantMsgId,
            session = sessionForCtx,
            onRagProgress = { stage, percentage, subStage ->
                messageManager.updateMessageProgress(
                    sessionId, assistantMsgId,
                    RagProgress(stage = stage, percentage = percentage, subStage = subStage)
                )
            },
            agentSystemPrompt = agentConfig.systemPrompt,
            sessionCustomPrompt = sessionForCtx.customPrompt
        )

        val contextResult = try {
            contextBuilder.buildContext(contextParams)
        } catch (e: Exception) {
            _error.update { "Context build failed: ${e.message}" }
            _isGenerating.update { false }
            _generationStatus.update { GenerationStatus.ERROR }
            viewModelScope.launch {
                kotlinx.coroutines.delay(2000)
                _generationStatus.update { GenerationStatus.IDLE }
            }
            return
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
            val flow = llmProvider.sendPrompt(request)
            _generationStatus.update { GenerationStatus.THINKING }
            
            flow.collect { chunk ->
                if (_generationStatus.value == GenerationStatus.THINKING && (chunk is StreamChunk.TextDelta || chunk is StreamChunk.Thinking)) {
                    _generationStatus.update { GenerationStatus.RECEIVING }
                }
                when (chunk) {
                    is StreamChunk.TextDelta -> {
                        accumulatedContent += chunk.content
                        chunk.reasoning?.let { accumulatedReasoning += it }
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
        if (accumulatedToolCalls.isEmpty() && accumulatedContent.isNotBlank()) {
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

                    // Sliding window and archiving logic
                    val windowSize = finalSession.inferenceParams?.activeContextWindow ?: 10
                    if (finalSession.messages.size > windowSize) {
                        val activeMsgs = getSafeActiveWindow(finalSession.messages, windowSize)
                        val activeMsgIds = activeMsgs.map { it.id }.toSet()
                        
                        val overflowMsgs = finalSession.messages.filter { it.id !in activeMsgIds && !it.isArchived }
                        
                        if (overflowMsgs.isNotEmpty()) {
                            // 1. Archive to RAG
                            if (sessionRagOpts?.enableMemory == true) {
                                postProcessor.archiveMessagesToRag(sessionId, overflowMsgs, finalSession.modelId ?: "")
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
                                val settingsPrefs = application.getSharedPreferences("nexara_settings", 0)
                                val summaryModelId = settingsPrefs.getString("preset_summary_model", "")
                                
                                val newSummary = summaryManager.summarize(
                                    oldSummary = finalSession.summary,
                                    overflowMessages = overflowMsgs,
                                    summaryModelId = summaryModelId,
                                    currentModelId = finalSession.modelId ?: ""
                                )
                                
                                if (newSummary != finalSession.summary) {
                                    sessionManager.updateSession(sessionId, mapOf("summary" to newSummary))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun loadSession(sessionId: String) {
        val existing = store.getSession(sessionId)
        if (existing != null) {
            _currentSessionId.update { sessionId }
            updateAgentName(existing.agentId)
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
                    val hydrated = session.copy(messages = messages)
                    store.update { state ->
                        if (state.sessions.any { it.id == sessionId }) state
                        else state.copy(sessions = state.sessions + hydrated)
                    }
                    _currentSessionId.update { sessionId }
                    updateAgentName(hydrated.agentId)
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
            
            val session = Session(
                id = sessionId,
                agentId = agentId,
                workspacePath = workspacePath,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
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

    fun updateRagOptions(options: com.promenar.nexara.data.model.RagOptions) {
        val sessionId = _currentSessionId.value ?: return
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
                MessageRole.USER -> ProtocolMessage(role = "user", content = msg.content)
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
     *   2. ```{ "function": { "name": "...", "arguments": "..." } } ```（OpenAI 风格）
     *   3. 裸 JSON 对象含 tool_name/tool/function 字段
     */
    private fun extractToolCallsFromText(content: String): List<ToolCall> {
        val results = mutableListOf<ToolCall>()

        // 匹配 Markdown 代码块中的 JSON（```json ... ``` 或 ``` ... ```）
        val codeBlockRegex = Regex(
            """```(?:json)?\s*\n(.*?)\n\s*```""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        // 匹配裸 JSON 对象（含 tool/function/name 字段）
        val bareJsonRegex = Regex(
            """\{\s*"(?:name|function|tool|tool_name)"\s*:\s*"[^"]+"[^}]*\}"""
        )

        val candidates = mutableListOf<String>()
        codeBlockRegex.findAll(content).forEach { candidates.add(it.groupValues[1]) }

        // 如果代码块中没找到，尝试直接从整个 content 中提取裸 JSON
        if (candidates.isEmpty()) {
            bareJsonRegex.findAll(content).forEach { candidates.add(it.value) }
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
                // 单行 JSON 未命中，尝试在代码块内再做裸 JSON 提取
                bareJsonRegex.findAll(trimmed).forEach { m ->
                    try {
                        val inner = Json.parseToJsonElement(m.value)
                        val tc = parseToolCallFromJson(inner, results.size)
                        if (tc != null && results.none { it.name == tc.name }) {
                            results.add(tc)
                        }
                    } catch (_: Exception) { /* 静默跳过 */ }
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
    private fun stripToolCallJsonBlocks(content: String): String {
        // 模式 1: 代码块包裹的 JSON（含任何工具调用字段）
        var result = content.replace(
            Regex(
                """```(?:json)?\s*\n(?:[\s\S]*?"(?:name|function|tool|tool_name)"[\s\S]*?)\n\s*```""",
                setOf(RegexOption.MULTILINE)
            )
        ) { "" }

        // 模式 2: 独立的裸 JSON 对象行（不是代码块的一部分）
        val bareJsonLineRegex = Regex(
            """^\s*\{\s*"(?:name|function|tool|tool_name)"\s*:\s*"[^"]+"[^}]*\}\s*$""",
            setOf(RegexOption.MULTILINE)
        )
        result = result.replace(bareJsonLineRegex) { "" }

        // 清理多余空行
        result = result.replace(Regex("\n{3,}"), "\n\n").trim()
        return result
    }
}
