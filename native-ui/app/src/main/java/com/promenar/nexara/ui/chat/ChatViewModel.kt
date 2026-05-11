package com.promenar.nexara.ui.chat

import android.app.Application
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.InferenceParams
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.SessionOptions
import com.promenar.nexara.data.remote.protocol.ProtocolCitation
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ProtocolToolCall
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.TokenUsage
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.UpdateMessageOptions
import com.promenar.nexara.data.rag.EmbeddingClient
import com.promenar.nexara.data.rag.MemoryManager
import com.promenar.nexara.data.rag.MemoryManagerRagAdapter
import com.promenar.nexara.data.rag.RecursiveCharacterTextSplitter
import com.promenar.nexara.data.rag.VectorStore
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.ui.chat.manager.ApprovalManager
import com.promenar.nexara.ui.chat.manager.ContextBuilder
import com.promenar.nexara.ui.chat.manager.ContextBuilderParams
import com.promenar.nexara.ui.chat.manager.KgProvider
import com.promenar.nexara.ui.chat.manager.MessageManager
import com.promenar.nexara.ui.chat.manager.PostProcessor
import com.promenar.nexara.ui.chat.manager.SessionManager
import com.promenar.nexara.ui.chat.manager.SummaryManager
import com.promenar.nexara.ui.chat.manager.ToolExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val session: Session? = null,
    val messages: List<Message> = emptyList(),
    val isGenerating: Boolean = false,
    val streamingContent: String = "",
    val error: String? = null,
    val approvalRequest: ApprovalRequest? = null
)

class ChatViewModel(
    private val application: Application,
    private val sessionRepository: ISessionRepository,
    private val messageRepository: IMessageRepository,
    private val llmProvider: LlmProvider,
    private val embeddingClient: EmbeddingClient? = null,
    private val vectorStore: VectorStore? = null,
    private val textSplitter: RecursiveCharacterTextSplitter? = null,
    private val memoryManager: MemoryManager? = null,
    private val kgProvider: KgProvider? = null,
    private val skillRegistry: com.promenar.nexara.ui.chat.manager.registry.SkillRegistry? = null
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

    private val _streamingContent = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)
    private val _isGenerating = MutableStateFlow(false)

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

    val uiState: StateFlow<ChatUiState> = combine(
        store.state,
        _currentSessionId,
        _isGenerating,
        _streamingContent,
        _error
    ) { chatState, sessionId, isGenerating, streamingContent, error ->
        val session = sessionId?.let { id -> chatState.sessions.find { it.id == id } }
        if (session != null) {
            updateTokenIndicator(session)
        }
        
        ChatUiState(
            session = session,
            messages = session?.messages ?: emptyList(),
            isGenerating = isGenerating,
            streamingContent = streamingContent,
            error = error,
            approvalRequest = session?.approvalRequest
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    init {
        approvalManager.setCallbacks(
            onGenerateMessage = { sessionId, content, isResumption ->
                generateMessage(sessionId, content, isResumption)
            },
            onExecuteTools = { sessionId, targetMessageId ->
                val session = store.getSession(sessionId) ?: return@setCallbacks
                val msg = session.messages.find { it.id == targetMessageId } ?: return@setCallbacks
                if (msg.toolCalls != null) {
                    toolExecutor.executeTools(sessionId, msg.toolCalls!!, targetMessageId)
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
        val userMsgId = "msg_${System.currentTimeMillis()}_user"
        val assistantMsgId = "msg_${System.currentTimeMillis()}_ai"

        _inputText.update { "" }
        _error.update { null }

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
        _streamingContent.update { "" }

        val agentDao = (application as NexaraApplication).database.agentDao()
        val agentEntity = agentDao.getById(sessionForCtx.agentId)
        val agentPrompt = agentEntity?.systemPrompt ?: ""

        val contextParams = ContextBuilderParams(
            sessionId = sessionId,
            content = effectiveUserContent,
            assistantMsgId = assistantMsgId,
            session = sessionForCtx,
            agentSystemPrompt = agentPrompt.ifBlank { sessionForCtx.customPrompt }
        )

        val contextResult = try {
            contextBuilder.buildContext(contextParams)
        } catch (e: Exception) {
            _error.update { "Context build failed: ${e.message}" }
            _isGenerating.update { false }
            return
        }

        val protocolMessages = buildProtocolMessages(sessionForCtx, contextResult.finalSystemPrompt)

        val activeTools = buildToolList(sessionForCtx)

        // Fallback logic: if session lacks settings, use agent's defaults
        val effectiveModel = sessionForCtx.modelId 
            ?: agentEntity?.model 
            ?: ""
        
        val effectiveParams = sessionForCtx.inferenceParams ?: com.promenar.nexara.data.model.InferenceParams(
            temperature = agentEntity?.temperature ?: 0.7,
            topP = agentEntity?.top_p ?: 0.9,
            maxTokens = agentEntity?.max_tokens ?: 4096
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
            webSearch = sessionForCtx.options?.webSearch,
            stream = true,
            streamTimeout = (effectiveParams.streamTimeout ?: 120).toLong() * 1000
        )

        var accumulatedContent = ""
        var accumulatedReasoning = ""
        var accumulatedTokens = TokenUsage()
        val accumulatedToolCalls = mutableListOf<ToolCall>()

        try {
            val flow = llmProvider.sendPrompt(request)
            flow.collect { chunk ->
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
        }

        messageManager.flushMessageUpdates(sessionId, assistantMsgId)
        _streamingContent.update { "" }

        if (accumulatedToolCalls.isNotEmpty() && currentCoroutineContext().isActive) {
            toolExecutor.executeTools(sessionId, accumulatedToolCalls.toList(), assistantMsgId)

            _isGenerating.update { false }
            if (currentCoroutineContext().isActive) {
                val newAssistantMsgId = "msg_${System.currentTimeMillis()}_ai"
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
                            val totalTokens = (accumulatedTokens?.total ?: 0)
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
            return
        }

        viewModelScope.launch {
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
                } else {
                    _error.update { "Session not found: $sessionId" }
                }
            } catch (e: Exception) {
                _error.update { "Failed to load session: ${e.message}" }
            }
        }
    }

    fun createNewSession(agentId: String) {
        viewModelScope.launch {
            val sessionId = "session_${System.currentTimeMillis()}"
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
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        llmProvider.cancel()
        _isGenerating.update { false }
        _streamingContent.update { "" }

        val sessionId = _currentSessionId.value
        if (sessionId != null) {
            approvalManager.setLoopStatus(sessionId, com.promenar.nexara.data.model.LoopStatus.PAUSED)
        }
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

        generationJob = viewModelScope.launch {
            messageManager.updateMessageContent(sessionId, messageId, newContent, UpdateMessageOptions())

            val toRemove = session.messages.drop(msgIndex + 1).map { it.id }
            toRemove.forEach { messageManager.deleteMessage(sessionId, it) }

            _inputText.update { "" }
            _error.update { null }

            val assistantMsgId = "msg_${System.currentTimeMillis()}_ai"
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
                viewModelScope.launch {
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
            "webSearch" -> options.copy(webSearch = enabled)
            else -> options
        }
        viewModelScope.launch {
            sessionManager.updateSessionOptions(sessionId, nextOptions)
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
        val options = session.options ?: return emptyList()
        if (!options.toolsEnabled) return emptyList()
        
        val prefs = application.getSharedPreferences("nexara_settings", 0)
        val enabledSkills = prefs.getStringSet("enabled_skills", null)
        
        if (enabledSkills.isNullOrEmpty()) return emptyList()
        
        val allowedIds = enabledSkills.toMutableList()
        
        if (options.webSearch == true && "web_search" !in allowedIds) {
            allowedIds.add("web_search")
        }
        if (options.webSearch != true) {
            allowedIds.removeAll { it == "web_search" || it.startsWith("search_") }
        }
        
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

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        llmProvider.cancel()
    }

    fun deleteMessage(messageId: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            messageManager.deleteMessage(sessionId, messageId) { stopGeneration() }
        }
    }

    fun editMessage(messageId: String, newContent: String) {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return
        val message = session.messages.find { it.id == messageId } ?: return
        
        viewModelScope.launch {
            // Delete all messages after this one
            messageManager.deleteMessagesAfter(sessionId, message.createdAt) { stopGeneration() }
            // Update this message content
            messageManager.updateMessageContent(sessionId, messageId, newContent)
            // Trigger regeneration
            generateMessage(sessionId, "", false)
        }
    }

    fun regenerateMessage(messageId: String) {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return
        val message = session.messages.find { it.id == messageId } ?: return
        
        viewModelScope.launch {
            // Delete this message and all after
            messageManager.deleteMessagesAfter(sessionId, message.createdAt) { stopGeneration() }
            // Trigger regeneration
            generateMessage(sessionId, "", false)
        }
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
                        llmProvider = app.llmProvider,
                        embeddingClient = app.embeddingClient,
                        vectorStore = app.vectorStore,
                        textSplitter = app.textSplitter,
                        memoryManager = app.memoryManager,
                        kgProvider = app.kgProvider,
                        skillRegistry = app.skillRegistry
                    ) as T
                }
            }
    }

    private fun updateTokenIndicator(session: Session) {
        val summary = session.summary ?: ""
        val activeMsgs = getSafeActiveWindow(session.messages, session.inferenceParams?.activeContextWindow ?: 10)
        
        val systemTokens = 500 // Estimated base
        val summaryTokens = PostProcessor.estimateTokens(summary)
        val activeTokens = activeMsgs.sumOf { PostProcessor.estimateTokens(it.content) }
        val ragTokens = 0 // Will be updated by context builder results
        
        val used = systemTokens + summaryTokens + activeTokens + ragTokens
        val max = com.promenar.nexara.data.model.findModelSpec(
            session.modelId ?: ""
        )?.contextLength ?: 128000
        
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
