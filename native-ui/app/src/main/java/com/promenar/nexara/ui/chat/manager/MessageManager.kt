package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.model.BillingUsage
import com.promenar.nexara.data.model.Citation
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.RagMetadata
import com.promenar.nexara.data.model.RagProgress
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.SessionStats
import com.promenar.nexara.data.model.TaskState
import com.promenar.nexara.data.model.TokenMetric
import com.promenar.nexara.data.model.TokenUsage
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.ToolResultArtifact
import com.promenar.nexara.data.model.UpdateMessageOptions
import com.promenar.nexara.data.model.json
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.ui.chat.ChatStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageManager(
    private val store: ChatStore,
    private val messageRepository: IMessageRepository,
    private val sessionRepository: ISessionRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private data class PendingUpdate(
        var content: String,
        var tokens: TokenUsage? = null,
        var reasoning: String? = null,
        var citations: List<Citation>? = null,
        var ragReferences: List<RagReference>? = null,
        var ragReferencesLoading: Boolean? = null,
        var ragMetadata: RagMetadata? = null,
        var thoughtSignature: String? = null,
        var taskState: TaskState? = null,
        var toolCalls: List<ToolCall>? = null,
        var executionSteps: List<ExecutionStep>? = null,
        var pendingApprovalToolIds: List<String>? = null,
        var toolResults: List<ToolResultArtifact>? = null,
        var isError: Boolean? = null,
        var errorMessage: String? = null,
        var isLongWait: Boolean? = null,
        var loopCount: Int? = null
    )

    private val pendingUpdates = mutableMapOf<String, PendingUpdate>()
    private val throttleJobs = mutableMapOf<String, Job>()

    private val dbPendingUpdates = mutableMapOf<String, MutableMap<String, Any?>>()
    private val dbDebounceJobs = mutableMapOf<String, Job>()

    companion object {
        private const val UI_THROTTLE_MS = 100L
        private const val DB_DEBOUNCE_MS = 500L
    }

    suspend fun addMessage(sessionId: String, message: Message) {
        store.update { state ->
            state.copy(
                sessions = state.sessions.map { s ->
                    if (s.id == sessionId) {
                        val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
                        s.copy(
                            messages = s.messages + message,
                            lastMessage = message.content,
                            time = timeFormatter.format(Date(message.createdAt))
                        )
                    } else s
                }
            )
        }

        try {
            messageRepository.insert(message, sessionId)
        } catch (_: Exception) {
        }
    }

    fun updateMessageContent(
        sessionId: String,
        messageId: String,
        content: String,
        options: UpdateMessageOptions? = null
    ) {
        val key = "$sessionId:$messageId"
        val current = pendingUpdates[key] ?: PendingUpdate(content = content)

        current.content = content
        options?.tokens?.let { current.tokens = it }
        options?.reasoning?.let { current.reasoning = it }
        options?.citations?.let { current.citations = it }
        options?.ragReferences?.let { current.ragReferences = it }
        options?.ragReferencesLoading?.let { current.ragReferencesLoading = it }
        options?.ragMetadata?.let { current.ragMetadata = it }
        options?.thoughtSignature?.let { current.thoughtSignature = it }
        options?.planningTask?.let { current.taskState = it }
        options?.toolCalls?.let { current.toolCalls = it }
        options?.executionSteps?.let { current.executionSteps = it }
        options?.pendingApprovalToolIds?.let { current.pendingApprovalToolIds = it }
        options?.toolResults?.let { current.toolResults = it }
        options?.isError?.let { current.isError = it }
        options?.errorMessage?.let { current.errorMessage = it }
        options?.isLongWait?.let { current.isLongWait = it }
        options?.loopCount?.let { current.loopCount = it }

        pendingUpdates[key] = current

        if (!throttleJobs.containsKey(key)) {
            throttleJobs[key] = scope.launch {
                delay(UI_THROTTLE_MS)
                flushUpdate(sessionId, messageId)
            }
        }
    }

    internal suspend fun flushUpdate(sessionId: String, messageId: String) {
        val key = "$sessionId:$messageId"
        val pending = pendingUpdates.remove(key) ?: return
        throttleJobs.remove(key)

        val session = store.getSession(sessionId) ?: return
        val message = session.messages.find { it.id == messageId } ?: return

        val oldTokens = message.tokens ?: TokenUsage()
        val newTokens = pending.tokens ?: oldTokens

        val tokensChanged = newTokens.total != oldTokens.total
        val deltaInput = newTokens.input - oldTokens.input
        val deltaOutput = newTokens.output - oldTokens.output
        val deltaTotal = newTokens.total - oldTokens.total

        val currentBilling = session.stats?.billing ?: BillingUsage()
        val updatedBilling = if (tokensChanged && deltaTotal > 0) {
            when (message.role) {
                MessageRole.ASSISTANT -> {
                    val newOutputCount = currentBilling.chatOutput.count + deltaOutput
                    val ragSystemDelta = deltaTotal - deltaOutput
                    val hasRag = pending.ragMetadata != null || pending.ragReferences != null
                            || message.ragMetadata != null || message.ragReferences != null

                    if (hasRag && ragSystemDelta > 0) {
                        currentBilling.copy(
                            chatOutput = TokenMetric(newOutputCount),
                            ragSystem = TokenMetric(currentBilling.ragSystem.count + ragSystemDelta),
                            total = currentBilling.total + deltaTotal
                        )
                    } else {
                        currentBilling.copy(
                            chatOutput = TokenMetric(newOutputCount),
                            chatInput = TokenMetric(currentBilling.chatInput.count + deltaInput),
                            total = currentBilling.total + deltaTotal
                        )
                    }
                }
                else -> {
                    currentBilling.copy(
                        chatInput = TokenMetric(currentBilling.chatInput.count + deltaInput),
                        total = currentBilling.total + deltaTotal
                    )
                }
            }
        } else {
            currentBilling
        }

        val dbUpdates = buildMap {
            put("content", pending.content)
            pending.tokens?.let { put("tokens", newTokens) }
            pending.reasoning?.let { put("reasoning", it) }
            pending.citations?.let { put("citations", it) }
            pending.ragReferences?.let { put("ragReferences", it) }
            pending.ragMetadata?.let { put("ragMetadata", it) }
            pending.thoughtSignature?.let { put("thoughtSignature", it) }
            pending.taskState?.let { put("planningTask", it) }
            pending.executionSteps?.let { put("executionSteps", it) }
            pending.pendingApprovalToolIds?.let { put("pendingApprovalToolIds", it) }
            pending.toolResults?.let { put("toolResults", it) }
            pending.toolCalls?.let { put("toolCalls", it) }
            pending.isError?.let { put("isError", it) }
            pending.errorMessage?.let { put("errorMessage", it) }
        }

        debouncedDbUpdate(sessionId, messageId, dbUpdates)

        store.update { state ->
            state.copy(
                sessions = state.sessions.map { s ->
                    if (s.id == sessionId) {
                        s.copy(
                            messages = s.messages.map { m ->
                                if (m.id == messageId) {
                                    applyPendingToMessage(m, pending, newTokens)
                                } else m
                            },
                            lastMessage = pending.content,
                            stats = SessionStats(
                                totalTokens = updatedBilling.total,
                                billing = updatedBilling
                            )
                        )
                    } else s
                }
            )
        }
    }

    private fun applyPendingToMessage(
        message: Message,
        pending: PendingUpdate,
        newTokens: TokenUsage
    ): Message = message.copy(
        content = pending.content,
        tokens = newTokens,
        reasoning = pending.reasoning ?: message.reasoning,
        citations = pending.citations ?: message.citations,
        ragReferences = pending.ragReferences ?: message.ragReferences,
        ragReferencesLoading = pending.ragReferencesLoading ?: message.ragReferencesLoading,
        ragMetadata = pending.ragMetadata ?: message.ragMetadata,
        thoughtSignature = pending.thoughtSignature ?: message.thoughtSignature,
        planningTask = pending.taskState ?: message.planningTask,
        toolCalls = pending.toolCalls ?: message.toolCalls,
        executionSteps = pending.executionSteps ?: message.executionSteps,
        pendingApprovalToolIds = pending.pendingApprovalToolIds ?: message.pendingApprovalToolIds,
        toolResults = pending.toolResults ?: message.toolResults,
        isError = pending.isError ?: message.isError,
        errorMessage = pending.errorMessage ?: message.errorMessage,
        isLongWait = pending.isLongWait ?: message.isLongWait,
        loopCount = pending.loopCount ?: message.loopCount
    )

    private fun debouncedDbUpdate(sessionId: String, messageId: String, updates: Map<String, Any?>) {
        val key = "$sessionId:$messageId"

        val existing = dbPendingUpdates[key] ?: mutableMapOf()
        existing.putAll(updates)
        dbPendingUpdates[key] = existing

        dbDebounceJobs[key]?.cancel()
        dbDebounceJobs[key] = scope.launch {
            delay(DB_DEBOUNCE_MS)
            val pendingUpdate = dbPendingUpdates.remove(key)
            dbDebounceJobs.remove(key)
            if (pendingUpdate != null) {
                try {
                    messageRepository.updatePartial(messageId, pendingUpdate)
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun deleteMessage(
        sessionId: String,
        messageId: String,
        onAbortGeneration: ((String) -> Unit)? = null
    ) {
        val state = store.get()
        if (state.currentGeneratingSessionId == sessionId) {
            val session = store.getSession(sessionId)
            if (session != null) {
                val lastMsg = session.messages.lastOrNull()
                if (lastMsg?.id == messageId) {
                    onAbortGeneration?.invoke(sessionId)
                }
            }
        }

        try {
            messageRepository.delete(messageId)
        } catch (_: Exception) {
        }

        store.update { state ->
            state.copy(
                sessions = state.sessions.map { s ->
                    if (s.id == sessionId) {
                        s.copy(messages = s.messages.filter { it.id != messageId })
                    } else s
                }
            )
        }
    }

    suspend fun deleteMessagesAfter(
        sessionId: String,
        timestamp: Long,
        onAbortGeneration: ((String) -> Unit)? = null
    ) {
        val state = store.get()
        if (state.currentGeneratingSessionId == sessionId) {
            onAbortGeneration?.invoke(sessionId)
        }

        try {
            messageRepository.deleteMessagesAfter(sessionId, timestamp)
        } catch (_: Exception) {
        }

        store.update { state ->
            state.copy(
                sessions = state.sessions.map { s ->
                    if (s.id == sessionId) {
                        s.copy(messages = s.messages.filter { it.createdAt < timestamp })
                    } else s
                }
            )
        }
    }

    fun updateMessageProgress(sessionId: String, messageId: String, progress: RagProgress) {
        store.updateMessageInSession(sessionId, messageId) { m ->
            m.copy(ragProgress = progress)
        }
    }

    fun updateMessageLayout(sessionId: String, messageId: String, height: Double) {
        val session = store.getSession(sessionId) ?: return
        val message = session.messages.find { it.id == messageId } ?: return

        if (message.layoutHeight == null || kotlin.math.abs((message.layoutHeight ?: 0.0) - height) > 10) {
            store.updateMessageInSession(sessionId, messageId) { m ->
                m.copy(layoutHeight = height)
            }
        }
    }

    suspend fun setVectorizationStatus(sessionId: String, messageIds: List<String>, status: String) {
        for (msgId in messageIds) {
            try {
                messageRepository.updateVectorizationStatus(
                    msgId, status,
                    isArchived = if (status == "success") true else null
                )
            } catch (_: Exception) {
            }
        }

        val idSet = messageIds.toSet()
        store.update { state ->
            state.copy(
                sessions = state.sessions.map { s ->
                    if (s.id == sessionId) {
                        s.copy(
                            messages = s.messages.map { m ->
                                if (idSet.contains(m.id)) {
                                    m.copy(
                                        vectorizationStatus = status,
                                        isArchived = if (status == "success") true else m.isArchived
                                    )
                                } else m
                            }
                        )
                    } else s
                }
            )
        }
    }

    suspend fun flushMessageUpdates(sessionId: String, messageId: String) {
        flushUpdate(sessionId, messageId)
    }

    fun hasPendingUpdates(sessionId: String, messageId: String): Boolean {
        return pendingUpdates.containsKey("$sessionId:$messageId")
    }
}
