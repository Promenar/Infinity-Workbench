package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.model.BillingUsage
import com.promenar.nexara.data.model.Citation
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.KgPath
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MessageManagerHooks(
    val beforeUiApply: suspend (String) -> Unit = {},
    val beforeDbWrite: suspend (String) -> Unit = {},
)

internal data class MessageCoordinationState(
    val lanes: Int = 0,
    val pendingUi: Int = 0,
    val inFlightUi: Int = 0,
    val uiJobs: Int = 0,
    val pendingDb: Int = 0,
    val dbJobs: Int = 0,
    val purging: Int = 0,
)

internal fun messageRemovalIds(messages: List<Message>, messageId: String): List<String> {
    val targetIndex = messages.indexOfFirst { it.id == messageId }
    if (targetIndex < 0) return listOf(messageId)
    val removalIds = linkedSetOf(messageId)
    if (messages[targetIndex].role == MessageRole.USER) {
        messages.drop(targetIndex + 1)
            .takeWhile { it.role != MessageRole.USER }
            .forEach { removalIds += it.id }
    }
    var expanded: Boolean
    do {
        expanded = false
        messages.forEach { message ->
            if (message.parentMessageId in removalIds && removalIds.add(message.id)) expanded = true
        }
    } while (expanded)
    return removalIds.toList()
}

class MessageManager(
    private val store: ChatStore,
    private val messageRepository: IMessageRepository,
    private val sessionRepository: ISessionRepository,
    /** 不绑定 ViewModel；生成协调器迁移后由应用级 scope 注入。 */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val hooks: MessageManagerHooks = MessageManagerHooks(),
) {
    private data class PendingUpdate(
        val epoch: Long,
        var content: String,
        var status: String? = null,
        var tokens: TokenUsage? = null,
        var reasoning: String? = null,
        var citations: List<Citation>? = null,
        var ragReferences: List<RagReference>? = null,
        var kgPaths: List<KgPath>? = null,
        var ragReferencesLoading: Boolean? = null,
        var ragMetadata: RagMetadata? = null,
        var thoughtSignature: String? = null,
        var taskState: TaskState? = null,
        var toolCalls: List<ToolCall>? = null,
        var clearToolCalls: Boolean = false,
        var executionSteps: List<ExecutionStep>? = null,
        var pendingApprovalToolIds: List<String>? = null,
        var toolResults: List<ToolResultArtifact>? = null,
        var isError: Boolean? = null,
        var errorMessage: String? = null,
        var clearError: Boolean = false,
        var isLongWait: Boolean? = null,
        var loopCount: Int? = null
    )

    private data class MessageLane(
        val mutex: Mutex = Mutex(),
        var epoch: Long = 0L,
    )

    private data class DbPendingUpdate(
        val epoch: Long,
        val updates: MutableMap<String, Any?>,
    )

    private val stateLock = Any()
    private val lanes = mutableMapOf<String, MessageLane>()
    private val purgingKeys = mutableSetOf<String>()
    private val pendingUpdates = mutableMapOf<String, PendingUpdate>()
    private val inFlightUiUpdates = mutableMapOf<String, PendingUpdate>()
    private val throttleJobs = mutableMapOf<String, Job>()

    private val dbPendingUpdates = mutableMapOf<String, DbPendingUpdate>()
    private val dbDebounceJobs = mutableMapOf<String, Job>()

    companion object {
        private const val UI_THROTTLE_MS = 100L
        private const val DB_DEBOUNCE_MS = 500L
    }

    suspend fun addMessage(sessionId: String, message: Message) {
        messageRepository.insert(message, sessionId)
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

    }

    /**
     * 将已经由跨表 Room 事务持久化的消息同步到内存 Store，禁止再次写库。
     */
    fun mirrorPersistedMessage(sessionId: String, message: Message) {
        store.update { state ->
            state.copy(
                sessions = state.sessions.map { session ->
                    if (session.id != sessionId || session.messages.any { it.id == message.id }) {
                        session
                    } else {
                        val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
                        session.copy(
                            messages = session.messages + message,
                            lastMessage = message.content,
                            time = timeFormatter.format(Date(message.createdAt)),
                        )
                    }
                },
            )
        }
    }

    suspend fun setPendingApprovalState(
        sessionId: String,
        messageId: String,
        toolCallIds: List<String>,
    ) = commitPendingApprovalState(sessionId, messageId, toolCallIds)

    suspend fun clearPendingApprovalState(sessionId: String, messageId: String) =
        commitPendingApprovalState(sessionId, messageId, null)

    suspend fun mirrorPersistedPendingApprovalState(
        sessionId: String,
        messageId: String,
        toolCallIds: List<String>?,
    ) = commitPendingApprovalState(
        sessionId = sessionId,
        messageId = messageId,
        toolCallIds = toolCallIds,
        persistApprovalField = false,
    )

    suspend fun flushNonApprovalUpdatesNow(sessionId: String, messageId: String) =
        commitPendingApprovalState(
            sessionId = sessionId,
            messageId = messageId,
            toolCallIds = store.getSession(sessionId)?.messages
                ?.firstOrNull { it.id == messageId }?.pendingApprovalToolIds,
            persistApprovalField = false,
            retryOnFailure = false,
        )

    private suspend fun commitPendingApprovalState(
        sessionId: String,
        messageId: String,
        toolCallIds: List<String>?,
        persistApprovalField: Boolean = true,
        retryOnFailure: Boolean = true,
    ) {
        val key = "$sessionId:$messageId"
        lateinit var lane: MessageLane
        var pendingUi: PendingUpdate? = null
        var inFlightUi: PendingUpdate? = null
        var pendingDb: DbPendingUpdate? = null
        var retryUpdates: Map<String, Any?>? = null
        var failure: Throwable? = null
        synchronized(stateLock) {
            purgingKeys += key
            lane = lanes.getOrPut(key) { MessageLane() }
            lane.epoch += 1
            throttleJobs.remove(key)?.cancel()
            dbDebounceJobs.remove(key)?.cancel()
            pendingUi = pendingUpdates.remove(key)
            inFlightUi = inFlightUiUpdates.remove(key)
            pendingDb = dbPendingUpdates.remove(key)
        }

        try {
            lane.mutex.withLock {
                val updates = mutableMapOf<String, Any?>()
                pendingDb?.updates?.let(updates::putAll)
                inFlightUi?.toDbUpdates()?.let(updates::putAll)
                pendingUi?.toDbUpdates()?.let(updates::putAll)
                updates.remove("pendingApprovalToolIds")
                if (!persistApprovalField) updates.remove("toolCalls")
                if (persistApprovalField) updates["pendingApprovalToolIds"] = toolCallIds
                if (updates.isNotEmpty()) {
                    hooks.beforeDbWrite(key)
                    if (persistApprovalField) {
                        messageRepository.updatePartial(messageId, updates)
                    } else {
                        runCatching { messageRepository.updatePartial(messageId, updates) }
                            .onFailure {
                                retryUpdates = updates.toMap()
                                failure = it
                            }
                    }
                }

                store.updateMessageInSession(sessionId, messageId) { current ->
                    val withLatestPending = pendingUi?.let { pending ->
                        applyPendingToMessage(
                            current,
                            pending,
                            pending.tokens ?: current.tokens ?: TokenUsage(),
                        )
                    } ?: current
                    withLatestPending.copy(pendingApprovalToolIds = toolCallIds)
                }
                synchronized(stateLock) {
                    invalidateMessageStateLocked(key, lane)
                    if (lanes[key] === lane) lanes.remove(key)
                }
            }
        } finally {
            finishPurge(setOf(key))
        }
        retryUpdates?.let { requeueNonApprovalDbUpdate(sessionId, messageId, it) }
        if (!retryOnFailure) failure?.let { throw it }
    }

    private fun requeueNonApprovalDbUpdate(
        sessionId: String,
        messageId: String,
        updates: Map<String, Any?>,
    ) {
        val epoch = synchronized(stateLock) {
            lanes.getOrPut("$sessionId:$messageId") { MessageLane() }.epoch
        }
        debouncedDbUpdate(sessionId, messageId, epoch, updates)
    }

    fun updateMessage(sessionId: String, messageId: String, message: Message) {
        scope.launch {
            try {
                val updates = mapOf(
                    "content" to message.content,
                    "status" to message.status,
                    "reasoning" to message.reasoning,
                    "isArchived" to message.isArchived,
                    "tokens" to message.tokens,
                    "isError" to message.isError,
                    "errorMessage" to message.errorMessage
                )
                messageRepository.updatePartial(messageId, updates)
            } catch (_: Exception) {}
            
            store.updateMessageInSession(sessionId, messageId) { message }
        }
    }

    fun updateMessageContent(
        sessionId: String,
        messageId: String,
        content: String,
        options: UpdateMessageOptions? = null
    ) {
        val key = "$sessionId:$messageId"
        if (store.getSession(sessionId)?.messages?.any { it.id == messageId } != true) return
        val jobToStart = synchronized(stateLock) {
            if (key in purgingKeys) return
            val lane = lanes.getOrPut(key) { MessageLane() }
            val current = pendingUpdates[key]
                ?.takeIf { it.epoch == lane.epoch }
                ?: PendingUpdate(epoch = lane.epoch, content = content)

            current.content = content
            options?.status?.let { current.status = it }
            options?.tokens?.let { current.tokens = it }
            options?.reasoning?.let { current.reasoning = it }
            options?.citations?.let { current.citations = it }
            options?.ragReferences?.let { current.ragReferences = it }
            options?.kgPaths?.let { current.kgPaths = it }
            options?.ragReferencesLoading?.let { current.ragReferencesLoading = it }
            options?.ragMetadata?.let { current.ragMetadata = it }
            options?.thoughtSignature?.let { current.thoughtSignature = it }
            options?.planningTask?.let { current.taskState = it }
            if (options?.clearToolCalls == true) {
                current.toolCalls = emptyList()
                current.clearToolCalls = true
            } else {
                options?.toolCalls?.let {
                    current.toolCalls = it
                    current.clearToolCalls = false
                }
            }
            options?.executionSteps?.let { current.executionSteps = it }
            options?.pendingApprovalToolIds?.let { current.pendingApprovalToolIds = it }
            options?.toolResults?.let { current.toolResults = it }
            if (options?.clearError == true) {
                current.isError = false
                current.errorMessage = null
                current.clearError = true
            } else {
                options?.isError?.let { current.isError = it }
                options?.errorMessage?.let { current.errorMessage = it }
            }
            options?.isLongWait?.let { current.isLongWait = it }
            options?.loopCount?.let { current.loopCount = it }
            pendingUpdates[key] = current

            if (throttleJobs.containsKey(key)) null else {
                scope.launch(start = CoroutineStart.LAZY) {
                    delay(UI_THROTTLE_MS)
                    flushUpdate(sessionId, messageId)
                }.also { throttleJobs[key] = it }
            }
        }
        jobToStart?.start()
    }

    internal suspend fun flushUpdate(sessionId: String, messageId: String) {
        val key = "$sessionId:$messageId"
        val lane = synchronized(stateLock) { lanes.getOrPut(key) { MessageLane() } }
        lane.mutex.withLock {
            val pending = synchronized(stateLock) {
                throttleJobs.remove(key)
                pendingUpdates.remove(key)?.takeIf { it.epoch == lane.epoch }
                    ?.also { inFlightUiUpdates[key] = it }
            } ?: return
            hooks.beforeUiApply(key)

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
                    val hasRag = pending.ragMetadata != null || pending.ragReferences != null || pending.kgPaths != null
                            || message.ragMetadata != null || message.ragReferences != null || message.kgPaths != null

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
            pending.status?.let { put("status", it) }
            pending.tokens?.let { put("tokens", newTokens) }
            pending.reasoning?.let { put("reasoning", it) }
            pending.citations?.let { put("citations", it) }
            pending.ragReferences?.let { put("ragReferences", it) }
            pending.kgPaths?.let { put("kgPaths", it) }
            pending.ragMetadata?.let { put("ragMetadata", it) }
            pending.thoughtSignature?.let { put("thoughtSignature", it) }
            pending.taskState?.let { put("planningTask", it) }
            pending.executionSteps?.let { put("executionSteps", it) }
            pending.pendingApprovalToolIds?.let { put("pendingApprovalToolIds", it) }
            pending.toolResults?.let { put("toolResults", it) }
            if (pending.clearToolCalls) put("toolCalls", emptyList<ToolCall>())
            else pending.toolCalls?.let { put("toolCalls", it) }
            if (pending.clearError) {
                put("isError", false)
                put("errorMessage", null)
            } else {
                pending.isError?.let { put("isError", it) }
                pending.errorMessage?.let { put("errorMessage", it) }
            }
        }

            debouncedDbUpdate(sessionId, messageId, pending.epoch, dbUpdates)

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
            synchronized(stateLock) {
                if (inFlightUiUpdates[key] === pending) inFlightUiUpdates.remove(key)
            }
        }
    }

    private fun applyPendingToMessage(
        message: Message,
        pending: PendingUpdate,
        newTokens: TokenUsage
    ): Message = message.copy(
        content = pending.content,
        status = pending.status ?: message.status,
        tokens = newTokens,
        reasoning = pending.reasoning ?: message.reasoning,
        citations = pending.citations ?: message.citations,
        ragReferences = pending.ragReferences ?: message.ragReferences,
        kgPaths = pending.kgPaths ?: message.kgPaths,
        ragReferencesLoading = pending.ragReferencesLoading ?: message.ragReferencesLoading,
        ragMetadata = pending.ragMetadata ?: message.ragMetadata,
        thoughtSignature = pending.thoughtSignature ?: message.thoughtSignature,
        planningTask = pending.taskState ?: message.planningTask,
        toolCalls = if (pending.clearToolCalls) emptyList() else pending.toolCalls ?: message.toolCalls,
        executionSteps = pending.executionSteps ?: message.executionSteps,
        pendingApprovalToolIds = pending.pendingApprovalToolIds ?: message.pendingApprovalToolIds,
        toolResults = pending.toolResults ?: message.toolResults,
        isError = if (pending.clearError) false else pending.isError ?: message.isError,
        errorMessage = if (pending.clearError) null else pending.errorMessage ?: message.errorMessage,
        isLongWait = pending.isLongWait ?: message.isLongWait,
        loopCount = pending.loopCount ?: message.loopCount
    )

    private fun debouncedDbUpdate(
        sessionId: String,
        messageId: String,
        epoch: Long,
        updates: Map<String, Any?>,
    ) {
        val key = "$sessionId:$messageId"
        val jobToStart = synchronized(stateLock) {
            val lane = lanes.getOrPut(key) { MessageLane() }
            if (lane.epoch != epoch) return
            val existing = dbPendingUpdates[key]
                ?.takeIf { it.epoch == epoch }
                ?: DbPendingUpdate(epoch, mutableMapOf())
            existing.updates.putAll(updates)
            dbPendingUpdates[key] = existing

            dbDebounceJobs.remove(key)?.cancel()
            scope.launch(start = CoroutineStart.LAZY) {
                delay(DB_DEBOUNCE_MS)
                flushDbUpdate(sessionId, key, messageId, lane)
            }.also { dbDebounceJobs[key] = it }
        }
        jobToStart.start()
    }

    private suspend fun flushDbUpdate(
        sessionId: String,
        key: String,
        messageId: String,
        lane: MessageLane,
    ) {
        var retry: Map<String, Any?>? = null
        var flushedEpoch: Long? = null
        lane.mutex.withLock {
            val pending = synchronized(stateLock) {
                dbDebounceJobs.remove(key)
                dbPendingUpdates.remove(key)?.takeIf { it.epoch == lane.epoch }
            } ?: return
            flushedEpoch = pending.epoch
            hooks.beforeDbWrite(key)
            try {
                messageRepository.updatePartial(messageId, pending.updates)
            } catch (_: Exception) {
                retry = pending.updates.toMap()
            }
        }
        retry?.let { updates ->
            requeueNonApprovalDbUpdate(sessionId, messageId, updates)
        } ?: synchronized(stateLock) {
            val epoch = flushedEpoch ?: return@synchronized
            val hasNewCoordinatedWork = pendingUpdates[key]?.epoch == epoch ||
                inFlightUiUpdates[key]?.epoch == epoch ||
                dbPendingUpdates[key]?.epoch == epoch ||
                throttleJobs.containsKey(key) ||
                dbDebounceJobs.containsKey(key) ||
                key in purgingKeys
            if (lanes[key] === lane && lane.epoch == epoch && !hasNewCoordinatedWork) {
                lanes.remove(key)
            }
        }
    }

    private fun PendingUpdate.toDbUpdates(): MutableMap<String, Any?> = buildMap<String, Any?> {
        put("content", content)
        status?.let { put("status", it) }
        tokens?.let { put("tokens", it) }
        reasoning?.let { put("reasoning", it) }
        citations?.let { put("citations", it) }
        ragReferences?.let { put("ragReferences", it) }
        kgPaths?.let { put("kgPaths", it) }
        ragReferencesLoading?.let { put("ragReferencesLoading", it) }
        ragMetadata?.let { put("ragMetadata", it) }
        thoughtSignature?.let { put("thoughtSignature", it) }
        taskState?.let { put("planningTask", it) }
        executionSteps?.let { put("executionSteps", it) }
        pendingApprovalToolIds?.let { put("pendingApprovalToolIds", it) }
        toolResults?.let { put("toolResults", it) }
        if (clearToolCalls) put("toolCalls", emptyList<ToolCall>())
        else toolCalls?.let { put("toolCalls", it) }
        if (clearError) {
            put("isError", false)
            put("errorMessage", null)
        } else {
            isError?.let { put("isError", it) }
            errorMessage?.let { put("errorMessage", it) }
        }
    }.toMutableMap()

    private fun clearRagFields(updates: MutableMap<String, Any?>) {
        updates["ragProgress"] = null
        updates["ragReferences"] = null
        updates["kgPaths"] = null
        updates["ragMetadata"] = null
        updates["citations"] = null
        updates["ragReferencesLoading"] = false
    }

    private fun applyPendingAndClearRag(
        message: Message,
        pending: PendingUpdate?,
    ): Message {
        val updated = if (pending == null) message else {
            applyPendingToMessage(message, pending, pending.tokens ?: message.tokens ?: TokenUsage())
        }
        return updated.copy(
            ragProgress = null,
            ragReferences = null,
            kgPaths = null,
            ragMetadata = null,
            citations = null,
            ragReferencesLoading = false,
        )
    }

    private fun clearMessageRagStateSerialized(
        sessionId: String,
        messageId: String,
        key: String,
        lane: MessageLane,
        pendingUi: PendingUpdate?,
        pendingDb: DbPendingUpdate?,
    ) {
        scope.launch {
            lane.mutex.withLock {
                val dbUpdates = pendingDb?.updates?.toMutableMap() ?: mutableMapOf()
                pendingUi?.toDbUpdates()?.let(dbUpdates::putAll)
                clearRagFields(dbUpdates)
                try {
                    hooks.beforeDbWrite(key)
                    messageRepository.updatePartial(messageId, dbUpdates)
                } catch (_: Exception) {
                }

                store.updateMessageInSession(sessionId, messageId) { message ->
                    applyPendingAndClearRag(message, pendingUi)
                }
            }
        }
    }

    private fun invalidateMessageStateLocked(key: String, lane: MessageLane) {
        lane.epoch += 1
        throttleJobs.remove(key)?.cancel()
        dbDebounceJobs.remove(key)?.cancel()
        pendingUpdates.remove(key)
        inFlightUiUpdates.remove(key)
        dbPendingUpdates.remove(key)
    }

    private suspend fun purgeMessageState(sessionId: String, messageIds: Collection<String>): Set<String> {
        val keyedLanes = synchronized(stateLock) {
            messageIds.distinct().associate { messageId ->
                val key = "$sessionId:$messageId"
                purgingKeys += key
                val lane = lanes.getOrPut(key) { MessageLane() }
                invalidateMessageStateLocked(key, lane)
                key to lane
            }
        }
        keyedLanes.forEach { (key, lane) ->
            lane.mutex.withLock {
                synchronized(stateLock) {
                    // 等待在飞 flush 后再次失效，覆盖等锁期间已排队的任何旧 epoch 状态。
                    invalidateMessageStateLocked(key, lane)
                    if (lanes[key] === lane) lanes.remove(key)
                }
            }
        }
        return keyedLanes.keys
    }

    private fun finishPurge(keys: Set<String>) {
        synchronized(stateLock) { purgingKeys.removeAll(keys) }
    }

    suspend fun deleteMessage(
        sessionId: String,
        messageId: String,
        onAbortGeneration: ((String) -> Unit)? = null
    ) {
        val storeMessages = store.getSession(sessionId)?.messages.orEmpty()
        val persistedMessages = messageRepository.getBySession(sessionId)
        val allMessages = (storeMessages + persistedMessages).distinctBy { it.id }
        val removalIds = messageRemovalIds(allMessages, messageId)
        val state = store.get()
        if (
            state.currentGeneratingSessionId == sessionId &&
            store.getSession(sessionId)?.messages?.lastOrNull()?.id in removalIds
        ) {
            onAbortGeneration?.invoke(sessionId)
        }

        val purgeKeys = purgeMessageState(sessionId, removalIds)
        try {
            check(messageRepository.deleteManyInSession(sessionId, removalIds) > 0) {
                "消息不存在或不属于当前会话"
            }

            store.update { state ->
                state.copy(
                    sessions = state.sessions.map { s ->
                        if (s.id == sessionId) {
                            s.copy(messages = s.messages.filterNot { it.id in removalIds })
                        } else s
                    }
                )
            }
        } finally {
            finishPurge(purgeKeys)
        }
    }

    /** 发送准备失败的幂等补偿：只按 session+message 删除，DB 无行时也清理 Store 与 lane。 */
    suspend fun discardPreparedMessage(sessionId: String, messageId: String) =
        discardPreparedMessages(sessionId, listOf(messageId))

    /** 同一发送回合的组级补偿；调用方按 assistant→user 传入，Store 只做一次原子过滤。 */
    suspend fun discardPreparedMessages(sessionId: String, messageIds: List<String>) {
        val orderedIds = messageIds.distinct()
        val purgeKeys = purgeMessageState(sessionId, orderedIds)
        var failure: Throwable? = null
        try {
            orderedIds.forEach { messageId ->
                try {
                    messageRepository.deleteInSession(sessionId, messageId)
                } catch (error: Throwable) {
                    if (failure == null) failure = error else failure?.addSuppressed(error)
                }
            }
            val idSet = orderedIds.toSet()
            store.update { state ->
                state.copy(
                    sessions = state.sessions.map { session ->
                        if (session.id == sessionId) {
                            session.copy(messages = session.messages.filterNot { it.id in idSet })
                        } else {
                            session
                        }
                    },
                )
            }
        } finally {
            finishPurge(purgeKeys)
        }
        failure?.let { throw it }
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

        val targetIds = store.getSession(sessionId)?.messages.orEmpty()
            .filter { it.createdAt >= timestamp }
            .map { it.id }
        val purgeKeys = purgeMessageState(sessionId, targetIds)
        try {
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
        } finally {
            finishPurge(purgeKeys)
        }
    }

    fun updateMessageProgress(sessionId: String, messageId: String, progress: RagProgress) {
        store.updateMessageInSession(sessionId, messageId) { m ->
            m.copy(ragProgress = progress)
        }
    }

    fun clearMessageRagState(sessionId: String, messageId: String) {
        val key = "$sessionId:$messageId"
        val snapshot = synchronized(stateLock) {
            val lane = lanes.getOrPut(key) { MessageLane() }
            lane.epoch += 1
            throttleJobs.remove(key)?.cancel()
            dbDebounceJobs.remove(key)?.cancel()
            val pendingUi = pendingUpdates.remove(key) ?: inFlightUiUpdates.remove(key)
            Triple(lane, pendingUi, dbPendingUpdates.remove(key))
        }
        clearMessageRagStateSerialized(
            sessionId = sessionId,
            messageId = messageId,
            key = key,
            lane = snapshot.first,
            pendingUi = snapshot.second,
            pendingDb = snapshot.third,
        )
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

    suspend fun clearMessages(sessionId: String) {
        val prefix = "$sessionId:"
        val coordinatedIds = synchronized(stateLock) {
            (lanes.keys + pendingUpdates.keys + inFlightUiUpdates.keys + throttleJobs.keys +
                dbPendingUpdates.keys + dbDebounceJobs.keys)
                .asSequence()
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
                .toSet()
        }
        val targetIds = store.getSession(sessionId)?.messages.orEmpty().mapTo(coordinatedIds.toMutableSet()) { it.id }
        val purgeKeys = purgeMessageState(sessionId, targetIds)
        try {
            try {
                messageRepository.deleteBySessionId(sessionId)
            } catch (_: Exception) {
            }

            store.update { state ->
                state.copy(
                    sessions = state.sessions.map { s ->
                        if (s.id == sessionId) {
                            s.copy(messages = emptyList(), lastMessage = null)
                        } else s
                    }
                )
            }
        } finally {
            finishPurge(purgeKeys)
        }
    }

    suspend fun flushMessageUpdates(sessionId: String, messageId: String) {
        flushUpdate(sessionId, messageId)
    }

    suspend fun flushGenerationTerminal(sessionId: String, messageId: String) {
        val key = "$sessionId:$messageId"
        lateinit var lane: MessageLane
        var pendingUi: PendingUpdate? = null
        var inFlightUi: PendingUpdate? = null
        var pendingDb: DbPendingUpdate? = null
        synchronized(stateLock) {
            purgingKeys += key
            lane = lanes.getOrPut(key) { MessageLane() }
            lane.epoch += 1
            throttleJobs.remove(key)?.cancel()
            dbDebounceJobs.remove(key)?.cancel()
            pendingUi = pendingUpdates.remove(key)
            inFlightUi = inFlightUiUpdates.remove(key)
            pendingDb = dbPendingUpdates.remove(key)
        }
        var failure: Throwable? = null
        try {
            lane.mutex.withLock {
                val current = store.getSession(sessionId)?.messages?.firstOrNull { it.id == messageId }
                    ?: return@withLock
                val merged = listOfNotNull(inFlightUi, pendingUi).fold(current) { message, pending ->
                    applyPendingToMessage(message, pending, pending.tokens ?: message.tokens ?: TokenUsage())
                }
                val updates = mutableMapOf<String, Any?>()
                pendingDb?.updates?.let(updates::putAll)
                inFlightUi?.toDbUpdates()?.let(updates::putAll)
                pendingUi?.toDbUpdates()?.let(updates::putAll)
                if (updates.isNotEmpty()) {
                    try {
                        messageRepository.updatePartial(messageId, updates)
                    } catch (error: Throwable) {
                        failure = error
                    }
                }
                store.updateMessageInSession(sessionId, messageId) { merged }
                synchronized(stateLock) {
                    invalidateMessageStateLocked(key, lane)
                    if (lanes[key] === lane) lanes.remove(key)
                }
            }
        } finally {
            finishPurge(setOf(key))
        }
        failure?.let { throw it }
    }

    suspend fun markGenerationTerminal(
        sessionId: String,
        messageId: String,
        content: String,
        status: String,
        errorMessage: String? = null,
    ) {
        val key = "$sessionId:$messageId"
        lateinit var lane: MessageLane
        var pendingUi: PendingUpdate? = null
        var inFlightUi: PendingUpdate? = null
        var pendingDb: DbPendingUpdate? = null
        synchronized(stateLock) {
            purgingKeys += key
            lane = lanes.getOrPut(key) { MessageLane() }
            lane.epoch += 1
            throttleJobs.remove(key)?.cancel()
            dbDebounceJobs.remove(key)?.cancel()
            pendingUi = pendingUpdates.remove(key)
            inFlightUi = inFlightUiUpdates.remove(key)
            pendingDb = dbPendingUpdates.remove(key)
        }
        var failure: Throwable? = null
        try {
            lane.mutex.withLock {
                val current = store.getSession(sessionId)?.messages?.firstOrNull { it.id == messageId }
                    ?: return@withLock
                val merged = listOfNotNull(inFlightUi, pendingUi).fold(current) { message, pending ->
                    applyPendingToMessage(message, pending, pending.tokens ?: message.tokens ?: TokenUsage())
                }
                val isError = status == "error"
                val clearUnconfirmedTools = status != "success"
                val terminal = merged.copy(
                    content = content,
                    status = status,
                    isError = isError,
                    errorMessage = errorMessage,
                    toolCalls = if (clearUnconfirmedTools) emptyList() else merged.toolCalls,
                )
                val updates = mutableMapOf<String, Any?>()
                pendingDb?.updates?.let(updates::putAll)
                inFlightUi?.toDbUpdates()?.let(updates::putAll)
                pendingUi?.toDbUpdates()?.let(updates::putAll)
                updates.putAll(
                    mapOf(
                        "content" to content,
                        "status" to status,
                        "isError" to isError,
                        "errorMessage" to errorMessage,
                    ),
                )
                if (clearUnconfirmedTools) updates["toolCalls"] = emptyList<ToolCall>()
                try {
                    messageRepository.updatePartial(messageId, updates)
                    store.updateMessageInSession(sessionId, messageId) { terminal }
                } catch (error: Throwable) {
                    failure = error
                }
                synchronized(stateLock) {
                    invalidateMessageStateLocked(key, lane)
                    if (lanes[key] === lane) lanes.remove(key)
                }
            }
        } finally {
            finishPurge(setOf(key))
        }
        failure?.let { throw it }
    }

    fun hasPendingUpdates(sessionId: String, messageId: String): Boolean {
        return synchronized(stateLock) {
            pendingUpdates.containsKey("$sessionId:$messageId")
        }
    }

    internal fun coordinationState(): MessageCoordinationState = synchronized(stateLock) {
        MessageCoordinationState(
            lanes = lanes.size,
            pendingUi = pendingUpdates.size,
            inFlightUi = inFlightUiUpdates.size,
            uiJobs = throttleJobs.size,
            pendingDb = dbPendingUpdates.size,
            dbJobs = dbDebounceJobs.size,
            purging = purgingKeys.size,
        )
    }
}
