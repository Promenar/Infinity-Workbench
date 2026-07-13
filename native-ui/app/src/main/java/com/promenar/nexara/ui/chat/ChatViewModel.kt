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
import com.promenar.nexara.data.manager.ProviderManager
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
import com.promenar.nexara.data.remote.ProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ProviderResolutionError
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.generation.ChatGenerationRunner
import com.promenar.nexara.data.generation.ChatGenerationRuntime
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.domain.repository.IAgentRepository
import com.promenar.nexara.ui.chat.manager.ApprovalManager
import com.promenar.nexara.ui.chat.manager.ContextBuilder
import com.promenar.nexara.ui.chat.manager.ContextBuilderParams
import com.promenar.nexara.ui.chat.manager.ContextBuilderResult
import com.promenar.nexara.ui.chat.manager.KgProvider
import com.promenar.nexara.ui.chat.manager.MessageManager
import com.promenar.nexara.ui.chat.manager.PostProcessor
import com.promenar.nexara.ui.chat.manager.SessionManager
import com.promenar.nexara.ui.chat.manager.SummaryManager
import com.promenar.nexara.ui.chat.manager.ToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class GenerationStatus {
    IDLE,
    UPLOADING,
    THINKING,
    RECEIVING,
    COMPLETED,
    ERROR
}

internal fun mergeToolCallsById(
    existing: List<ToolCall>,
    incoming: List<ToolCall>,
): List<ToolCall> {
    val merged = linkedMapOf<String, ToolCall>()
    existing.forEach { call -> merged.putIfAbsent(call.id, call) }
    incoming.forEach { call ->
        val previous = merged[call.id]
        merged[call.id] = if (previous == null) {
            call
        } else {
            previous.copy(
                name = call.name.ifBlank { previous.name },
                arguments = call.arguments.ifBlank { previous.arguments },
            )
        }
    }
    return merged.values.toList()
}

internal fun stableFallbackToolCallId(
    prefix: String,
    name: String,
    arguments: String,
    index: Int,
): String {
    val raw = "$prefix\u0000$name\u0000$arguments\u0000$index"
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
    return "${prefix}_" + digest.take(16).joinToString("") { "%02x".format(it) }
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
    val backgroundWarning: BackgroundGenerationWarning? = null,
    val approvalRequest: ApprovalRequest? = null
)

enum class BackgroundWarningKind { FGS_UNAVAILABLE, NOTIFICATIONS_UNAVAILABLE }
data class BackgroundGenerationWarning(
    val taskId: String,
    val kind: BackgroundWarningKind,
    val message: String,
)
data class BackgroundServiceStoppedError(val taskId: String, val message: String)
data class NotificationPermissionRequest(val taskId: String)

internal fun ContextBuilderResult.hasPersistableRagContext(): Boolean =
    ragContext.isNotBlank() || ragReferences.isNotEmpty() || citations.isNotEmpty() ||
        kgPaths.isNotEmpty() || ragUsage != null

internal fun ContextBuilderResult.toMessageRagUpdateOptions(): UpdateMessageOptions? {
    if (ragReferences.isEmpty() && citations.isEmpty() && kgPaths.isEmpty()) return null
    val hasReferenceMetadata = ragReferences.isNotEmpty() || citations.isNotEmpty()
    return UpdateMessageOptions(
        ragReferences = ragReferences.ifEmpty { null },
        citations = citations.ifEmpty { null },
        kgPaths = kgPaths.ifEmpty { null },
        ragMetadata = if (hasReferenceMetadata) {
            RagMetadata(
                chunkCount = ragReferences.size,
                totalTokens = ragUsage?.ragSystem ?: 0,
                retrievalTimeMs = 0,
            )
        } else null,
    )
}

class ChatViewModel(
    private val application: Application,
    private val sessionRepository: ISessionRepository,
    private val messageRepository: IMessageRepository,
    private val agentRepository: IAgentRepository,
    private val llmProvider: LlmProvider,
    providerRequestRouter: ProviderRequestRouter,
    private val configResolver: AgentConfigResolver,
    providerResolutionDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val localLlmProviderFactory: (String) -> LlmProvider = { modelId ->
        LlmProvider.local((application as NexaraApplication).localInferenceEngine, modelId)
    },
    private val embeddingClient: EmbeddingClient? = null,
    private val vectorStore: VectorStore? = null,
    private val textSplitter: RecursiveCharacterTextSplitter? = null,
    private val memoryManager: MemoryManager? = null,
    private val kgProvider: KgProvider? = null,
    private val skillRegistry: com.promenar.nexara.ui.chat.manager.registry.SkillRegistry? = null,
    private val exportSessionUseCase: ExportSessionUseCase? = null,
    generationCoordinatorOverride: com.promenar.nexara.domain.generation.GenerationCoordinator? = null,
    generationPresentationStoreOverride: com.promenar.nexara.data.generation.GenerationPresentationStore? = null,
    generationForegroundControllerOverride:
        com.promenar.nexara.background.generation.GenerationForegroundController? = null,
    stringResourceResolverOverride: ((Int) -> String)? = null,
) : ViewModel() {

    private val store = (application as NexaraApplication).chatStore
    private val generationCoordinator = generationCoordinatorOverride
        ?: (application as NexaraApplication).generationCoordinator
    private val generationPresentationStore = generationPresentationStoreOverride
        ?: (application as NexaraApplication).generationPresentationStore
    private val generationForegroundController = generationForegroundControllerOverride
        ?: (application as NexaraApplication).generationForegroundController
    private val stringResourceResolver = stringResourceResolverOverride ?: application::getString

    private val sessionManager = SessionManager(store, sessionRepository)
    private val messageManager = MessageManager(store, messageRepository, sessionRepository)
    private val toolLedger = (application as NexaraApplication).toolExecutionLedger
    private val toolExecutor = ToolExecutor(
        store,
        messageManager,
        skillRegistry,
        (application as NexaraApplication).taskRepository,
        toolLedger,
    )
    private val summaryManager = SummaryManager(llmProvider)
    private val approvalManager = ApprovalManager(
        store,
        toolLedger,
        messageManager,
        sessionRepository,
    )

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    private val _currentSessionId = MutableStateFlow<String?>(null)
    private val _agentName = MutableStateFlow("")

    private val _streamingContent = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)
    private val _backgroundServiceStoppedError =
        MutableStateFlow<BackgroundServiceStoppedError?>(null)
    private val _backgroundWarning = MutableStateFlow<BackgroundGenerationWarning?>(null)
    private var foregroundTrackedTaskId: String? = null
    private val mutableNotificationPermissionRequest =
        MutableStateFlow<NotificationPermissionRequest?>(null)
    val notificationPermissionRequests: StateFlow<NotificationPermissionRequest?> =
        mutableNotificationPermissionRequest
    private val _providerResolutionFailure = MutableStateFlow<ProviderResolution.Failure?>(null)
    val providerResolutionFailure: StateFlow<ProviderResolution.Failure?> = _providerResolutionFailure
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
        val sessionId = _currentSessionId.value ?: return ""
        val taskId = generationCoordinator.observe(sessionId).value?.taskId ?: return ""
        val port = generationPresentationStore.port(sessionId, taskId)
        val id = port.addPostProcessTask(type, detail)
        port.updatePostProcessTask(id, status, progress, detail)
        return id
    }

    fun updatePostProcessTask(id: String, status: PostProcessStatus? = null, progress: Float? = null, detail: String? = null) {
        _currentSessionId.value?.let { sessionId ->
            val taskId = generationCoordinator.observe(sessionId).value?.taskId ?: return@let
            generationPresentationStore.port(sessionId, taskId)
                .updatePostProcessTask(id, status, progress, detail)
        }
    }

    fun removePostProcessTask(id: String) {
        _currentSessionId.value?.let { sessionId ->
            val taskId = generationCoordinator.observe(sessionId).value?.taskId ?: return@let
            generationPresentationStore.port(sessionId, taskId).removePostProcessTask(id)
        }
    }

    private var generationJob: Job? = null
    private val sendPreparationMutex = Mutex()

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<ChatUiState> = combine(
        store.state,
        _currentSessionId,
        _agentName,
        _isGenerating,
        _isLoading,
        _generationStatus,
        _streamingContent,
        _error,
        _backgroundServiceStoppedError,
        _backgroundWarning,
    ) { args: Array<Any?> ->
        val state = args[0] as com.promenar.nexara.ui.chat.ChatState
        val sessionId = args[1] as String?
        val agentName = args[2] as String
        val isGenerating = args[3] as Boolean
        val isLoading = args[4] as Boolean
        val status = args[5] as GenerationStatus
        val streamingContent = args[6] as String
        val error = args[7] as String?
        val backgroundServiceStoppedError = args[8] as BackgroundServiceStoppedError?
        val backgroundWarning = args[9] as BackgroundGenerationWarning?

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
            error = backgroundServiceStoppedError?.message ?: error,
            backgroundWarning = backgroundWarning,
            approvalRequest = session?.approvalRequest
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    init {
        viewModelScope.launch {
            _currentSessionId.collectLatest { sessionId ->
                if (sessionId == null) return@collectLatest
                resetSessionTransientState()
                restoreHistoricalRagProjection(sessionId)
                try {
                    generationPresentationStore.observe(sessionId).collect { presentation ->
                        if (presentation == null) {
                            _streamingContent.value = ""
                            _postProcessTasks.value = emptyList()
                            _ragPhases.value = emptyList()
                            if (_generationStatus.value != GenerationStatus.ERROR) {
                                _error.value = null
                                _providerResolutionFailure.value = null
                                _generationStatus.value = GenerationStatus.IDLE
                            }
                            return@collect
                        }
                        _ragPhases.value = presentation.ragPhases
                        _streamingContent.value = presentation.streamingContent
                        _error.value = presentation.error
                        _providerResolutionFailure.value = presentation.providerFailure
                        _isGenerating.value = presentation.generating
                        _postProcessTasks.value = presentation.postProcessTasks
                        _generationStatus.value = when {
                            presentation.handledFailure -> GenerationStatus.ERROR
                            presentation.phase == com.promenar.nexara.domain.generation.GenerationPhase.THINKING ->
                                GenerationStatus.THINKING
                            presentation.phase == com.promenar.nexara.domain.generation.GenerationPhase.STREAMING ->
                                GenerationStatus.RECEIVING
                            presentation.phase == com.promenar.nexara.domain.generation.GenerationPhase.COMPLETED ->
                                GenerationStatus.COMPLETED
                            presentation.phase == com.promenar.nexara.domain.generation.GenerationPhase.FAILED ->
                                GenerationStatus.ERROR
                            presentation.phase == com.promenar.nexara.domain.generation.GenerationPhase.CANCELLED ->
                                GenerationStatus.IDLE
                            presentation.phase == com.promenar.nexara.domain.generation.GenerationPhase.PERSISTENCE_FAILED ->
                                GenerationStatus.ERROR
                            else -> _generationStatus.value
                        }
                    }
                } finally {
                    generationPresentationStore.release(sessionId)
                }
            }
        }
        viewModelScope.launch {
            _currentSessionId.collectLatest { sessionId ->
                if (sessionId == null) return@collectLatest
                var observedTaskId: String? = null
                try {
                    combine(
                        generationCoordinator.observe(sessionId),
                        generationPresentationStore.observe(sessionId),
                    ) { task, presentation -> task to presentation }
                        .collect { (task, presentation) ->
                        if (task == null) {
                            _isGenerating.value = false
                            observedTaskId?.let {
                                clearBackgroundWarningForTask(it)
                                clearNotificationPermissionRequestForTask(it)
                            }
                            observedTaskId = null
                            if (_generationStatus.value == GenerationStatus.COMPLETED) {
                                _generationStatus.value = GenerationStatus.IDLE
                            }
                            return@collect
                        }
                        observedTaskId = task.taskId
                        _isGenerating.value = task.phase !in setOf(
                            com.promenar.nexara.domain.generation.GenerationPhase.COMPLETED,
                            com.promenar.nexara.domain.generation.GenerationPhase.FAILED,
                            com.promenar.nexara.domain.generation.GenerationPhase.CANCELLED,
                            com.promenar.nexara.domain.generation.GenerationPhase.PERSISTENCE_FAILED,
                            com.promenar.nexara.domain.generation.GenerationPhase.WAITING_APPROVAL,
                        )
                        task.error?.let { _error.value = it.message }
                        _generationStatus.value = when (task.phase) {
                            com.promenar.nexara.domain.generation.GenerationPhase.PREPARING,
                            com.promenar.nexara.domain.generation.GenerationPhase.BUILDING_CONTEXT,
                            com.promenar.nexara.domain.generation.GenerationPhase.CONNECTING -> GenerationStatus.UPLOADING
                            com.promenar.nexara.domain.generation.GenerationPhase.THINKING -> GenerationStatus.THINKING
                            com.promenar.nexara.domain.generation.GenerationPhase.STREAMING -> GenerationStatus.RECEIVING
                            com.promenar.nexara.domain.generation.GenerationPhase.COMPLETED -> GenerationStatus.COMPLETED
                            com.promenar.nexara.domain.generation.GenerationPhase.FAILED -> GenerationStatus.ERROR
                            com.promenar.nexara.domain.generation.GenerationPhase.CANCELLED -> GenerationStatus.IDLE
                            com.promenar.nexara.domain.generation.GenerationPhase.PERSISTENCE_FAILED -> GenerationStatus.ERROR
                            com.promenar.nexara.domain.generation.GenerationPhase.WAITING_APPROVAL -> _generationStatus.value
                            com.promenar.nexara.domain.generation.GenerationPhase.POST_PROCESSING -> GenerationStatus.RECEIVING
                        }
                        if (task.phase in TERMINAL_GENERATION_PHASES) {
                            clearBackgroundWarningForTask(task.taskId)
                            clearNotificationPermissionRequestForTask(task.taskId)
                            val terminalPresentation = presentation
                                ?.takeIf { it.taskId == task.taskId }
                                ?.takeIf {
                                    it.handledFailure ||
                                        it.phase?.let { phase -> phase in TERMINAL_GENERATION_PHASES } == true
                                }
                                ?: return@collect
                            _error.value = terminalPresentation.error ?: _error.value
                            _providerResolutionFailure.value =
                                terminalPresentation.providerFailure ?: _providerResolutionFailure.value
                            _ragPhases.value = terminalPresentation.ragPhases
                            _postProcessTasks.value = terminalPresentation.postProcessTasks
                            if (task.phase != com.promenar.nexara.domain.generation.GenerationPhase.PERSISTENCE_FAILED) {
                                generationCoordinator.acknowledgeTerminal(task.taskId)
                            }
                        }
                        }
                } finally {
                    generationCoordinator.release(sessionId)
                }
            }
        }
        viewModelScope.launch {
            generationForegroundController.failures.collect { failure ->
                if (
                    failure.outcome ==
                    com.promenar.nexara.background.generation.ForegroundServiceFailureOutcome.GENERATION_STOPPED &&
                    foregroundTrackedTaskId == failure.taskId
                ) {
                    _backgroundServiceStoppedError.value = BackgroundServiceStoppedError(
                        taskId = failure.taskId,
                        message = stringResourceResolver(
                            R.string.generation_background_service_stopped,
                        ),
                    )
                }
            }
        }
        approvalManager.setCallbacks(
            onGenerateMessage = { sessionId, content, isResumption ->
                generateMessage(sessionId, content, isResumption)
            },
            onExecuteTools = { sessionId, targetMessageId, allowedToolCallIds ->
                val session = store.getSession(sessionId) ?: return@setCallbacks
                val msg = session.messages.find { it.id == targetMessageId } ?: return@setCallbacks
                if (msg.toolCalls != null) {
                    toolExecutor.executeTools(
                        sessionId,
                        targetMessageId,
                        msg.toolCalls,
                        allowedToolCallIds,
                    )
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

        cancelPendingPreparation()
        generationJob = viewModelScope.launch {
            sendPreparationMutex.withLock {
                _generationStatus.update { GenerationStatus.UPLOADING }
                _isGenerating.update { true }
                _error.update { null }
                _backgroundServiceStoppedError.value = null
                _backgroundWarning.value = null
                _providerResolutionFailure.value = null

                val imageDataUrls = if (imageUris.isNotEmpty()) {
                    val converted = withContext(Dispatchers.IO) {
                        imageUris.map { uri -> uriToDataUrl(uri) }
                    }
                    if (converted.any { it == null }) {
                        _error.update { "Image read failed. Please remove the failed attachment and try again." }
                        _generationStatus.update { GenerationStatus.ERROR }
                        _isGenerating.update { false }
                        return@withLock
                    }
                    converted.filterNotNull()
                } else {
                    emptyList()
                }

                enqueuePreparedUserTurn(sessionId, session, text, imageDataUrls)
            }
        }
    }

    private fun uriToDataUrl(uri: Uri): String? {
        return try {
            val bytes = application.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val mimeType = application.contentResolver.getType(uri) ?: "image/jpeg"
            "data:$mimeType;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun enqueuePreparedUserTurn(
        sessionId: String,
        session: Session,
        text: String,
        imageDataUrls: List<String>
    ) {
        val userMsgId = IdGenerator.message("user")
        val assistantMsgId = IdGenerator.message("ai")

        try {
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

            when (val start = generateMessage(sessionId, "", false, assistantMsgId, userMsgId, text)) {
                is com.promenar.nexara.domain.generation.StartGenerationResult.Started,
                is com.promenar.nexara.domain.generation.StartGenerationResult.Existing -> {
                    _inputText.value = ""
                    sessionManager.updateSessionDraft(sessionId, null)
                }
                is com.promenar.nexara.domain.generation.StartGenerationResult.Busy,
                is com.promenar.nexara.domain.generation.StartGenerationResult.Rejected,
                null -> {
                    messageManager.discardPreparedMessages(
                        sessionId,
                        listOf(assistantMsgId, userMsgId),
                    )
                    _inputText.value = text
                    sessionManager.updateSessionDraft(sessionId, text)
                    _isGenerating.value = false
                    _generationStatus.value = GenerationStatus.ERROR
                    _error.value = when (start) {
                        is com.promenar.nexara.domain.generation.StartGenerationResult.Busy ->
                            "已有会话正在生成：${start.activeSessionId}"
                        is com.promenar.nexara.domain.generation.StartGenerationResult.Rejected -> start.error.message
                        else -> "生成请求未能启动"
                    }
                }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    messageManager.discardPreparedMessages(
                        sessionId,
                        listOf(assistantMsgId, userMsgId),
                    )
                }
                    .exceptionOrNull()?.let(failure::addSuppressed)
                val draftToPreserve = _inputText.value.ifBlank { text }
                _inputText.value = draftToPreserve
                runCatching { sessionManager.updateSessionDraft(sessionId, draftToPreserve) }
                    .exceptionOrNull()?.let(failure::addSuppressed)
                _isGenerating.value = false
            }
            throw failure
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun generateMessage(
        sessionId: String,
        interventionContent: String,
        isResumption: Boolean,
        existingAssistantMsgId: String? = null,
        userMsgId: String? = null,
        userContent: String? = null,
    ): com.promenar.nexara.domain.generation.StartGenerationResult? {
        val session = store.getSession(sessionId) ?: return null
        val assistantMsgId = existingAssistantMsgId
            ?: session.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
            ?: return null
        val effectiveUserContent = userContent
            ?: interventionContent.ifBlank { session.pendingIntervention }
            ?: session.messages.lastOrNull { it.role == MessageRole.USER }?.content
            ?: ""
        val request = com.promenar.nexara.domain.generation.GenerationRequest(
            sessionId = sessionId,
            assistantMessageId = assistantMsgId,
            userMessageId = userMsgId,
            userContent = effectiveUserContent,
            imageDataUrls = session.messages.lastOrNull { it.id == userMsgId }?.userImages.orEmpty(),
            runtimePolicy = com.promenar.nexara.domain.generation.GenerationRuntimePolicy.BACKGROUND_ALLOWED,
            requestId = IdGenerator.message("generation"),
        )
        return generationCoordinator.start(request).also { result ->
            when (result) {
            is com.promenar.nexara.domain.generation.StartGenerationResult.Started -> {
                generationCoordinator.observe(sessionId).value
                    ?.takeIf { it.taskId == result.taskId }
                    ?.let(::trackForegroundGeneration)
            }
            is com.promenar.nexara.domain.generation.StartGenerationResult.Existing -> {
                trackForegroundGeneration(result.snapshot)
            }
            is com.promenar.nexara.domain.generation.StartGenerationResult.Busy -> {
                _error.value = "已有会话正在生成：${result.activeSessionId}"
            }
            is com.promenar.nexara.domain.generation.StartGenerationResult.Rejected -> {
                _error.value = result.error.message
                _generationStatus.value = GenerationStatus.ERROR
            }
        }
        }
    }

    private fun trackForegroundGeneration(
        snapshot: com.promenar.nexara.domain.generation.GenerationTaskSnapshot,
    ) {
        val current = generationCoordinator.observe(snapshot.sessionId).value
        if (current?.taskId != snapshot.taskId || current.phase in FOREGROUND_STOPPING_PHASES) return
        foregroundTrackedTaskId = current.taskId
        when (generationForegroundController.track(current)) {
            com.promenar.nexara.background.generation.ForegroundStartResult.StartedWithNotifications -> {
                clearBackgroundWarningForTask(current.taskId)
            }
            com.promenar.nexara.background.generation.ForegroundStartResult.StartedWithoutNotifications -> {
                publishBackgroundWarning(current.taskId, BackgroundWarningKind.NOTIFICATIONS_UNAVAILABLE)
                mutableNotificationPermissionRequest.value =
                    NotificationPermissionRequest(current.taskId)
            }
            is com.promenar.nexara.background.generation.ForegroundStartResult.NotAllowed,
            is com.promenar.nexara.background.generation.ForegroundStartResult.PermissionDenied -> {
                publishBackgroundWarning(current.taskId, BackgroundWarningKind.FGS_UNAVAILABLE)
            }
        }
    }

    fun onNotificationPermissionResult(taskId: String, granted: Boolean) {
        clearNotificationPermissionRequestForTask(taskId)
        if (granted) clearBackgroundWarningForTask(taskId)
    }

    fun clearBackgroundWarning(taskId: String) {
        clearBackgroundWarningForTask(taskId)
    }

    private fun publishBackgroundWarning(taskId: String, kind: BackgroundWarningKind) {
        _backgroundWarning.value = BackgroundGenerationWarning(
            taskId = taskId,
            kind = kind,
            message = stringResourceResolver(
                when (kind) {
                    BackgroundWarningKind.FGS_UNAVAILABLE -> R.string.generation_background_unavailable
                    BackgroundWarningKind.NOTIFICATIONS_UNAVAILABLE ->
                        R.string.generation_notifications_unavailable
                },
            ),
        )
    }

    private fun clearBackgroundWarningForTask(taskId: String) {
        if (_backgroundWarning.value?.taskId == taskId) _backgroundWarning.value = null
    }

    private fun clearNotificationPermissionRequestForTask(taskId: String) {
        if (mutableNotificationPermissionRequest.value?.taskId == taskId) {
            mutableNotificationPermissionRequest.value = null
        }
    }

    fun loadSession(sessionId: String) {
        _inputText.value = ""
        resetSessionTransientState()
        val existing = store.getSession(sessionId)
        if (existing != null) {
            if (existing.workspaceRootUuid.isNullOrBlank()) {
                viewModelScope.launch { ensureSessionWorkspace(sessionId) }
            }
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
                    if (hydrated.workspaceRootUuid.isNullOrBlank()) {
                        val root = (application as NexaraApplication).workspaceRepository.ensureSessionRoot(sessionId)
                        hydrated = hydrated.copy(workspaceRootUuid = root.uuid, workspacePath = root.physicalRootPath)
                        sessionManager.updateSession(
                            sessionId,
                            mapOf("workspaceRootUuid" to root.uuid, "workspacePath" to root.physicalRootPath),
                        )
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

    private fun resetSessionTransientState() {
        _streamingContent.value = ""
        _postProcessTasks.value = emptyList()
        _ragPhases.value = emptyList()
        _error.value = null
        _backgroundServiceStoppedError.value = null
        _backgroundWarning.value = null
        foregroundTrackedTaskId = null
        mutableNotificationPermissionRequest.value = null
        _providerResolutionFailure.value = null
        _generationStatus.value = GenerationStatus.IDLE
        _isGenerating.value = false
    }

    private fun restoreHistoricalRagProjection(sessionId: String) {
        val lastMessage = store.getSession(sessionId)?.messages
            ?.lastOrNull { it.role == MessageRole.ASSISTANT && !it.ragReferences.isNullOrEmpty() }
            ?: return
        _ragPhases.value = listOf(
            RagPhase(
                "retrieved",
                "已检索",
                PhaseStatus.DONE,
                100,
                "${lastMessage.ragReferences!!.size} 个来源",
            ),
        )
    }

    fun createNewSession(agentId: String) {
        viewModelScope.launch {
            val sessionId = IdGenerator.session()
            val defaultOptions = getDefaultRagOptions()
            val agent = agentRepository.getById(agentId)
            val defaultModelId = agent?.modelId
                ?.takeIf { it.isNotBlank() }
                ?: resolveDefaultModelId()
            val session = Session(
                id = sessionId,
                agentId = agentId,
                modelId = defaultModelId,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                ragOptions = defaultOptions
            )
            sessionManager.addSession(session)
            val root = (application as NexaraApplication).workspaceRepository.ensureSessionRoot(sessionId)
            sessionManager.updateSession(
                sessionId,
                mapOf("workspaceRootUuid" to root.uuid, "workspacePath" to root.physicalRootPath),
            )
            _currentSessionId.update { sessionId }
            updateAgentName(agentId)
        }
    }

    private suspend fun ensureSessionWorkspace(sessionId: String) {
        val root = (application as NexaraApplication).workspaceRepository.ensureSessionRoot(sessionId)
        sessionManager.updateSession(
            sessionId,
            mapOf("workspaceRootUuid" to root.uuid, "workspacePath" to root.physicalRootPath),
        )
    }

    private fun resolveDefaultModelId(): String? {
        return runCatching {
            val providerManager = ProviderManager.getInstance()
            providerManager.summaryModelId.value.takeIf { it.isNotBlank() }
                ?: providerManager.getMainConfiguredModelId()
        }.getOrNull()
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
        cancelCurrentSessionGeneration()
        val sessionId = _currentSessionId.value
        if (sessionId != null) {
            viewModelScope.launch {
                val waitingForApproval = store.getSession(sessionId)?.loopStatus ==
                    com.promenar.nexara.data.model.LoopStatus.WAITING_FOR_APPROVAL
                if (waitingForApproval) {
                    approvalManager.cancelPendingApproval(sessionId)
                } else {
                    approvalManager.setLoopStatus(
                        sessionId,
                        com.promenar.nexara.data.model.LoopStatus.PAUSED,
                    )
                }
            }
        }
    }

    private fun cancelPendingPreparation() {
        generationJob?.cancel()
        generationJob = null
    }

    private fun cancelCurrentSessionGeneration() {
        cancelPendingPreparation()
        val sessionId = _currentSessionId.value ?: return
        generationCoordinator.observe(sessionId).value?.taskId?.let { taskId ->
            generationCoordinator.cancel(
                taskId,
                com.promenar.nexara.domain.generation.CancellationReason.USER,
            )
        }
    }

    fun retryLastMessage() {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return

        val lastUserMsg = session.messages.lastOrNull { it.role == MessageRole.USER }
        if (lastUserMsg == null) return

        val lastAssistantMsg = session.messages.lastOrNull { it.role == MessageRole.ASSISTANT }

        cancelCurrentSessionGeneration()
        generationJob = viewModelScope.launch {
            try {
                if (lastAssistantMsg != null) {
                    messageManager.deleteMessage(sessionId, lastAssistantMsg.id)
                }
                val updatedSession = store.getSession(sessionId) ?: return@launch
                enqueuePreparedUserTurn(
                    sessionId = sessionId,
                    session = updatedSession,
                    text = lastUserMsg.content,
                    imageDataUrls = lastUserMsg.userImages.orEmpty(),
                )
            } catch (_: Exception) {
                _error.update { "无法安全删除旧回复，已取消重试。" }
                _generationStatus.update { GenerationStatus.ERROR }
                _isGenerating.update { false }
            }
        }
    }

    fun approveRequest(intervention: String? = null) {
        val sessionId = _currentSessionId.value ?: return
        cancelCurrentSessionGeneration()
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
        if (_backgroundServiceStoppedError.value != null) {
            _backgroundServiceStoppedError.value = null
            return
        }
        _currentSessionId.value?.let { sessionId ->
            generationCoordinator.observe(sessionId).value
                ?.takeIf {
                    it.phase == com.promenar.nexara.domain.generation.GenerationPhase.PERSISTENCE_FAILED
                }
                ?.let { generationCoordinator.acknowledgeTerminal(it.taskId) }
        }
        _error.update { null }
        _providerResolutionFailure.value = null
    }

    fun regenerateLastMessage() {
        retryLastMessage()
    }

    fun editAndResend(messageId: String, newContent: String) {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return

        val msgIndex = session.messages.indexOfFirst { it.id == messageId }
        if (msgIndex < 0) return

        cancelCurrentSessionGeneration()
        generationJob = viewModelScope.launch {
            messageManager.updateMessageContent(sessionId, messageId, newContent, UpdateMessageOptions())

            val toRemove = session.messages.drop(msgIndex + 1).map { it.id }
            toRemove.forEach { id ->
                if (store.getSession(sessionId)?.messages?.any { it.id == id } == true) {
                    messageManager.deleteMessage(sessionId, id)
                }
            }

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
                cancelCurrentSessionGeneration()
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
            "enableGeminiSearch" -> options.copy(enableGeminiSearch = enabled)
            "webSearch" -> options.copy(webSearch = enabled)
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
        _currentSessionId.value?.let { sessionId ->
            generationCoordinator.release(sessionId)
            generationPresentationStore.release(sessionId)
        }
        super.onCleared()
        // 生成由应用级 owner 持有；ViewModel 销毁只移除观察者，不终止请求。
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
        
        cancelCurrentSessionGeneration()
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
        val userMessage = if (message.role == MessageRole.USER) {
            message
        } else {
            session.messages
                .filter { it.role == MessageRole.USER && it.createdAt < message.createdAt }
                .maxByOrNull { it.createdAt }
        } ?: return
        
        cancelCurrentSessionGeneration()
        generationJob = viewModelScope.launch {
            // Delete all messages strictly after this one with backup
            backupAndTruncate(sessionId, userMessage.createdAt + 1)
            
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
            generateMessage(sessionId, "", false, assistantMsgId, userMessage.id, userMessage.content)
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
            generationCoordinator.release(sessionId, discardTerminal = true)
            generationPresentationStore.release(sessionId)
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
        private val TERMINAL_GENERATION_PHASES = setOf(
            com.promenar.nexara.domain.generation.GenerationPhase.COMPLETED,
            com.promenar.nexara.domain.generation.GenerationPhase.FAILED,
            com.promenar.nexara.domain.generation.GenerationPhase.CANCELLED,
            com.promenar.nexara.domain.generation.GenerationPhase.PERSISTENCE_FAILED,
        )
        private val FOREGROUND_STOPPING_PHASES = TERMINAL_GENERATION_PHASES +
            com.promenar.nexara.domain.generation.GenerationPhase.WAITING_APPROVAL

        fun factory(
            application: Application,
            generationCoordinatorOverride:
                com.promenar.nexara.domain.generation.GenerationCoordinator? = null,
        ): ViewModelProvider.Factory =
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
                        providerRequestRouter = app.providerRequestRouter,
                        configResolver = app.configResolver,
                        providerResolutionDispatcher = Dispatchers.IO,
                        localLlmProviderFactory = { modelId ->
                            LlmProvider.local(app.localInferenceEngine, modelId)
                        },
                        embeddingClient = app.embeddingClient,
                        vectorStore = app.vectorStore,
                        textSplitter = app.textSplitter,
                        memoryManager = app.memoryManager,
                        kgProvider = app.kgProvider,
                        skillRegistry = app.skillRegistry,
                        exportSessionUseCase = ExportSessionUseCase(
                            app.messageRepository as com.promenar.nexara.domain.repository.IMessageRepository,
                            app.sessionRepository as com.promenar.nexara.domain.repository.ISessionRepository
                        ),
                        generationCoordinatorOverride = generationCoordinatorOverride,
                    ) as T
                }
            }
    }

    private fun providerRoutingErrorMessage(failure: ProviderResolution.Failure): String =
        if (failure.reason == ProviderResolutionError.LOCAL_INFERENCE_UNAVAILABLE) {
            application.getString(R.string.local_inference_release_unavailable)
        } else {
            "Provider 路由失败：${failure.reason.name}；请打开对应 Provider 设置" +
                (failure.providerId?.let { "（$it）" } ?: "")
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

}
