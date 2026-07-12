package com.promenar.nexara.data.repository

import androidx.room.withTransaction
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.dao.ToolExecutionLedgerDao
import com.promenar.nexara.data.local.db.entity.ToolExecutionLedgerEntity
import com.promenar.nexara.data.local.db.entity.ToolLedgerStatus
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.toEntity
import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.data.model.LoopStatus
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.security.MessageDigest

data class ToolExecutionKey(
    val sessionId: String,
    val assistantMessageId: String,
    val toolCallId: String,
)

typealias ToolLedgerState = ToolLedgerStatus

sealed interface ToolExecutionOutcome {
    val state: ToolLedgerState

    data object Succeeded : ToolExecutionOutcome {
        override val state = ToolLedgerState.SUCCEEDED
    }

    data class Failed(val error: String) : ToolExecutionOutcome {
        override val state = ToolLedgerState.FAILED
    }
}

data class ToolTerminalRequest(
    val key: ToolExecutionKey,
    val toolName: String,
    val thoughtSignature: String? = null,
)

enum class ToolApprovalCreation { CREATED, EXISTING, CONFLICT }

data class ApprovalTransition(
    val sessionId: String,
    val currentAssistantMessageId: String,
    val loopStatus: LoopStatus,
    val approvalRequest: ApprovalRequest?,
    val nextAssistantMessageId: String? = null,
    val nextPendingToolCallIds: List<String> = emptyList(),
    val terminalMessages: List<Message> = emptyList(),
)

interface ToolExecutionLedger {
    suspend fun register(
        key: ToolExecutionKey,
        toolName: String,
        requiresApproval: Boolean,
    ): ToolLedgerState

    suspend fun approve(keys: Set<ToolExecutionKey>): Int
    suspend fun reject(keys: Set<ToolExecutionKey>): Int
    suspend fun cancel(keys: Set<ToolExecutionKey>): Int
    suspend fun timeout(keys: Set<ToolExecutionKey>): Int
    suspend fun claim(key: ToolExecutionKey): Boolean
    suspend fun finish(key: ToolExecutionKey, outcome: ToolExecutionOutcome): Boolean
    suspend fun finishWithResult(
        key: ToolExecutionKey,
        toolName: String,
        content: String,
        thoughtSignature: String?,
        outcome: ToolExecutionOutcome,
        images: String? = null,
    ): Message?
    suspend fun terminalizeWithResults(
        requests: Set<ToolTerminalRequest>,
        state: ToolLedgerState,
    ): List<Message>
    suspend fun state(key: ToolExecutionKey): ToolLedgerState?
    suspend fun recoverInterruptedRunning(error: String): Int
    suspend fun createToolApproval(
        keySessionId: String,
        assistantMessageId: String,
        toolCalls: List<ToolCall>,
        pendingToolCallIds: Set<String>,
        request: ApprovalRequest,
    ): ToolApprovalCreation
    suspend fun completeToolApproval(sessionId: String, assistantMessageId: String): ApprovalTransition
    suspend fun recoverApprovalState(): Int
}

class ToolExecutionLedgerRepository(
    private val database: NexaraDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : ToolExecutionLedger {
    private val dao: ToolExecutionLedgerDao = database.toolExecutionLedgerDao()
    override suspend fun register(
        key: ToolExecutionKey,
        toolName: String,
        requiresApproval: Boolean,
    ): ToolLedgerState {
        val now = clock()
        val initial = if (requiresApproval) {
            ToolLedgerState.PENDING_APPROVAL
        } else {
            ToolLedgerState.APPROVED
        }
        dao.insert(
            ToolExecutionLedgerEntity(
                sessionId = key.sessionId,
                assistantMessageId = key.assistantMessageId,
                toolCallId = key.toolCallId,
                toolName = toolName,
                requiresApproval = requiresApproval,
                status = initial,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return state(key) ?: error("工具账本注册失败")
    }

    override suspend fun approve(keys: Set<ToolExecutionKey>): Int = database.withTransaction {
        keys.sumOf { key ->
            dao.approve(key.sessionId, key.assistantMessageId, key.toolCallId, clock())
        }
    }

    override suspend fun reject(keys: Set<ToolExecutionKey>): Int = terminalizeKeys(
        keys,
        ToolLedgerState.REJECTED,
    ).size

    override suspend fun cancel(keys: Set<ToolExecutionKey>): Int = terminalizeKeys(
        keys,
        ToolLedgerState.CANCELLED,
    ).size

    override suspend fun timeout(keys: Set<ToolExecutionKey>): Int = terminalizeKeys(
        keys,
        ToolLedgerState.TIMED_OUT,
    ).size

    override suspend fun claim(key: ToolExecutionKey): Boolean = dao.claim(
        key.sessionId,
        key.assistantMessageId,
        key.toolCallId,
        clock(),
    ) == 1

    override suspend fun finish(key: ToolExecutionKey, outcome: ToolExecutionOutcome): Boolean {
        val entry = dao.get(key.sessionId, key.assistantMessageId, key.toolCallId) ?: return false
        val content = when (outcome) {
            ToolExecutionOutcome.Succeeded -> "工具执行完成。"
            is ToolExecutionOutcome.Failed -> "工具执行失败，请检查工具参数与配置后重试。"
        }
        return finishWithResult(key, entry.toolName, content, null, outcome) != null
    }

    override suspend fun finishWithResult(
        key: ToolExecutionKey,
        toolName: String,
        content: String,
        thoughtSignature: String?,
        outcome: ToolExecutionOutcome,
        images: String?,
    ): Message? = database.withTransaction {
        val createdAt = clock()
        val message = terminalMessage(
            request = ToolTerminalRequest(key, toolName, thoughtSignature),
            state = outcome.state,
            content = content,
            createdAt = createdAt,
            images = images,
        )
        val changed = dao.finish(
            sessionId = key.sessionId,
            assistantMessageId = key.assistantMessageId,
            toolCallId = key.toolCallId,
            terminalStatus = outcome.state,
            resultMessageId = message.id,
            error = (outcome as? ToolExecutionOutcome.Failed)?.error?.take(MAX_ERROR_LENGTH),
            updatedAt = createdAt,
        )
        if (changed != 1) return@withTransaction null
        database.messageDao().insert(message.toEntity(key.sessionId))
        message
    }

    override suspend fun terminalizeWithResults(
        requests: Set<ToolTerminalRequest>,
        state: ToolLedgerState,
    ): List<Message> {
        val (allowed, error, content) = terminalDecision(state) ?: return emptyList()
        return database.withTransaction {
            val orderedRequests = requests.map { request ->
                request to dao.get(
                    request.key.sessionId,
                    request.key.assistantMessageId,
                    request.key.toolCallId,
                )
            }.sortedWith(
                compareBy<Pair<ToolTerminalRequest, ToolExecutionLedgerEntity?>> { it.second?.createdAt ?: Long.MAX_VALUE }
                    .thenBy { it.first.key.toolCallId },
            ).map { it.first }
            val messages = orderedRequests.mapNotNull { request ->
                val createdAt = clock()
                val message = terminalMessage(request, state, content, createdAt)
                val changed = dao.decideTerminal(
                    sessionId = request.key.sessionId,
                    assistantMessageId = request.key.assistantMessageId,
                    toolCallId = request.key.toolCallId,
                    allowedStatuses = allowed,
                    terminalStatus = state,
                    resultMessageId = message.id,
                    error = error,
                    updatedAt = createdAt,
                )
                if (changed != 1) return@mapNotNull null
                database.messageDao().insert(message.toEntity(request.key.sessionId))
                message
            }
            if (requests.isNotEmpty()) {
                val sessionId = requests.first().key.sessionId
                val assistantMessageId = requests.first().key.assistantMessageId
                check(requests.all {
                    it.key.sessionId == sessionId && it.key.assistantMessageId == assistantMessageId
                })
                database.messageDao().updatePendingApprovalToolIds(assistantMessageId, null)
                database.sessionDao().updateToolApprovalState(
                    sessionId,
                    null,
                    LoopStatus.PAUSED.toSerializedName(),
                    clock(),
                )
            }
            messages
        }
    }

    override suspend fun state(key: ToolExecutionKey): ToolLedgerState? = dao.get(
        key.sessionId,
        key.assistantMessageId,
        key.toolCallId,
    )?.status

    override suspend fun recoverInterruptedRunning(error: String): Int {
        database.withTransaction { dao.deleteOrphans() }
        val running = dao.getRunning()
        return running.count { entry ->
            runCatching {
                database.withTransaction {
                    val key = ToolExecutionKey(entry.sessionId, entry.assistantMessageId, entry.toolCallId)
                    val createdAt = clock()
                    val message = terminalMessage(
                        ToolTerminalRequest(key, entry.toolName),
                        ToolLedgerState.FAILED,
                        "工具执行因进程中断而失败，请确认外部结果后重新发起。",
                        createdAt,
                    )
                    val changed = dao.finish(
                        entry.sessionId,
                        entry.assistantMessageId,
                        entry.toolCallId,
                        ToolLedgerState.FAILED,
                        message.id,
                        error.take(MAX_ERROR_LENGTH),
                        createdAt,
                    )
                    if (changed == 1) {
                        database.messageDao().insert(message.toEntity(entry.sessionId))
                        true
                    } else {
                        false
                    }
                }
            }.getOrDefault(false)
        }
    }

    override suspend fun createToolApproval(
        keySessionId: String,
        assistantMessageId: String,
        toolCalls: List<ToolCall>,
        pendingToolCallIds: Set<String>,
        request: ApprovalRequest,
    ): ToolApprovalCreation = database.withTransaction {
        val assistant = database.messageDao().getById(assistantMessageId)
            ?.takeIf { it.sessionId == keySessionId && it.role == "assistant" }
            ?: return@withTransaction ToolApprovalCreation.CONFLICT
        val awaiting = dao.getAwaitingApprovalForSession(keySessionId)
        val conflictingApproval = awaiting
            .firstOrNull { it.assistantMessageId != assistantMessageId }
        if (conflictingApproval != null) return@withTransaction ToolApprovalCreation.CONFLICT
        val distinctCalls = toolCalls.distinctBy { it.id }
        if (distinctCalls.size != toolCalls.size || pendingToolCallIds.isEmpty() ||
            pendingToolCallIds.any { pendingId -> distinctCalls.none { it.id == pendingId } }
        ) return@withTransaction ToolApprovalCreation.CONFLICT
        val orderedPendingIds = distinctCalls.filter { it.id in pendingToolCallIds }.map { it.id }
        val existingSame = if (awaiting.any { it.assistantMessageId == assistantMessageId }) {
            dao.getForAssistant(keySessionId, assistantMessageId)
        } else {
            emptyList()
        }
        if (existingSame.isNotEmpty()) {
            val persistedCalls = assistant.toolCalls?.let {
                runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
            }
            val persistedPending = assistant.pendingApprovalToolIds?.let {
                runCatching { json.decodeFromString<List<String>>(it) }.getOrNull()
            }
            val expectedStates = distinctCalls.associate { call ->
                call.id to if (call.id in pendingToolCallIds) {
                    ToolLedgerState.PENDING_APPROVAL
                } else {
                    ToolLedgerState.APPROVED
                }
            }
            val persistedStates = existingSame.associate { it.toolCallId to it.status }
            val persistedApprovalRequirements = existingSame.associate {
                it.toolCallId to it.requiresApproval
            }
            val persistedRequest = database.sessionDao().getById(keySessionId)?.approvalRequest?.let {
                runCatching { json.decodeFromString<ApprovalRequest>(it) }.getOrNull()
            }
            return@withTransaction if (
                persistedCalls == distinctCalls &&
                    persistedPending == orderedPendingIds &&
                    persistedStates == expectedStates &&
                    persistedApprovalRequirements == distinctCalls.associate {
                        it.id to (it.id in pendingToolCallIds)
                    } &&
                    persistedRequest == request
            ) ToolApprovalCreation.EXISTING else ToolApprovalCreation.CONFLICT
        }
        val now = clock()
        distinctCalls.forEach { call ->
            val expectedState = if (call.id in pendingToolCallIds) {
                ToolLedgerState.PENDING_APPROVAL
            } else {
                ToolLedgerState.APPROVED
            }
            dao.insert(
                ToolExecutionLedgerEntity(
                    sessionId = keySessionId,
                    assistantMessageId = assistant.id,
                    toolCallId = call.id,
                    toolName = call.name,
                    requiresApproval = call.id in pendingToolCallIds,
                    status = expectedState,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            check(dao.get(keySessionId, assistantMessageId, call.id)?.status == expectedState) {
                "重复工具调用状态与审批请求冲突"
            }
        }
        check(database.messageDao().updateToolApprovalPayload(
            assistantMessageId,
            json.encodeToString(distinctCalls),
            json.encodeToString(orderedPendingIds),
        ) == 1)
        check(database.sessionDao().updateToolApprovalState(
            keySessionId,
            json.encodeToString(request),
            LoopStatus.WAITING_FOR_APPROVAL.toSerializedName(),
            now,
        ) == 1)
        ToolApprovalCreation.CREATED
    }

    override suspend fun completeToolApproval(
        sessionId: String,
        assistantMessageId: String,
    ): ApprovalTransition =
        database.withTransaction {
            check(database.messageDao().updatePendingApprovalToolIds(assistantMessageId, null) == 1)
            val terminalMessages = mutableListOf<Message>()
            val validRemaining = dao.getAwaitingApprovalForSession(sessionId)
                .groupBy { it.assistantMessageId }
                .entries
                .mapNotNull { (messageId, entries) ->
                    val message = database.messageDao().getById(messageId)
                    val callIds = message?.toolCalls?.let {
                        runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
                    }?.mapTo(mutableSetOf()) { it.id }.orEmpty()
                    if (message != null && message.sessionId == sessionId &&
                        message.role == "assistant" && entries.all { it.toolCallId in callIds }
                    ) {
                        message to entries
                    } else {
                        terminalMessages += invalidateApprovalGroup(entries, message)
                        message?.let { database.messageDao().updatePendingApprovalToolIds(it.id, null) }
                        null
                    }
                }
            val remaining = validRemaining.minWithOrNull(compareBy<Pair<com.promenar.nexara.data.local.db.entity.MessageEntity, List<ToolExecutionLedgerEntity>>> {
                    it.first.createdAt
                }.thenBy { it.first.id })
            validRemaining.filterNot { it == remaining }.forEach { (message, entries) ->
                terminalMessages += invalidateApprovalGroup(entries, message)
                database.messageDao().updatePendingApprovalToolIds(message.id, null)
            }
            if (remaining == null) {
                check(database.sessionDao().updateToolApprovalState(
                    sessionId,
                    null,
                    LoopStatus.RUNNING.toSerializedName(),
                    clock(),
                ) == 1)
                ApprovalTransition(
                    sessionId = sessionId,
                    currentAssistantMessageId = assistantMessageId,
                    loopStatus = LoopStatus.RUNNING,
                    approvalRequest = null,
                    terminalMessages = terminalMessages,
                )
            } else {
                val (message, entries) = remaining
                val stableEntries = stableLedgerEntries(entries)
                val ids = stableEntries.map { it.toolCallId }.distinct()
                val nextRequest = recoveryApprovalRequestModel(stableEntries)
                database.messageDao().updatePendingApprovalToolIds(message.id, json.encodeToString(ids))
                database.sessionDao().updateToolApprovalState(
                    sessionId,
                    json.encodeToString(nextRequest),
                    LoopStatus.WAITING_FOR_APPROVAL.toSerializedName(),
                    clock(),
                )
                ApprovalTransition(
                    sessionId = sessionId,
                    currentAssistantMessageId = assistantMessageId,
                    loopStatus = LoopStatus.WAITING_FOR_APPROVAL,
                    approvalRequest = nextRequest,
                    nextAssistantMessageId = message.id,
                    nextPendingToolCallIds = ids,
                    terminalMessages = terminalMessages,
                )
            }
        }

    override suspend fun recoverApprovalState(): Int {
        database.withTransaction {
            dao.deleteOrphans()
            database.messageDao().clearPendingApprovalWithoutLedger()
        }
        dao.getUnclaimedSafeApprovals().forEach { entry ->
            runCatching {
                database.withTransaction {
                    val parent = database.messageDao().getById(entry.assistantMessageId)
                        ?.takeIf { it.sessionId == entry.sessionId && it.role == "assistant" }
                    val createdAt = clock()
                    val terminal = parent?.let {
                        terminalMessage(
                            ToolTerminalRequest(
                                ToolExecutionKey(entry.sessionId, entry.assistantMessageId, entry.toolCallId),
                                entry.toolName,
                                it.thoughtSignature,
                            ),
                            ToolLedgerState.FAILED,
                            "安全工具在执行前中断，请重新发起。",
                            createdAt,
                        )
                    }
                    if (dao.invalidateAwaitingApproval(
                            entry.sessionId,
                            entry.assistantMessageId,
                            entry.toolCallId,
                            terminal?.id,
                            "安全工具未claim即进程中断",
                            createdAt,
                        ) == 1
                    ) {
                        terminal?.let { database.messageDao().insert(it.toEntity(entry.sessionId)) }
                    }
                }
            }
        }
        val sessions = dao.getAwaitingApproval().groupBy { it.sessionId }
        val activeSessions = mutableSetOf<String>()
        sessions.forEach { (sessionId, sessionEntries) ->
            val hasActive = runCatching {
                database.withTransaction {
                    val groups = sessionEntries.groupBy { it.assistantMessageId }
                    val validGroups = mutableListOf<Pair<com.promenar.nexara.data.local.db.entity.MessageEntity, List<ToolExecutionLedgerEntity>>>()
                    val invalidGroups = mutableListOf<Pair<com.promenar.nexara.data.local.db.entity.MessageEntity?, List<ToolExecutionLedgerEntity>>>()
                    groups.forEach { (assistantMessageId, entries) ->
                        val message = database.messageDao().getById(assistantMessageId)
                        val calls = message?.toolCalls?.let {
                            runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
                        }
                        val callIds = calls?.mapTo(mutableSetOf()) { it.id }.orEmpty()
                        val pendingRaw = message?.pendingApprovalToolIds
                        val decodedPending = pendingRaw?.let {
                            runCatching { json.decodeFromString<List<String>>(it) }.getOrNull()
                        }
                        val valid = message != null && message.sessionId == sessionId &&
                            message.role == "assistant" && calls != null &&
                            (pendingRaw == null || decodedPending != null) &&
                            entries.all { it.toolCallId in callIds } &&
                            decodedPending.orEmpty().all { it in callIds }
                        if (valid) validGroups += message!! to entries else invalidGroups += message to entries
                    }

                    val selected = validGroups.minWithOrNull(
                        compareBy<Pair<com.promenar.nexara.data.local.db.entity.MessageEntity, List<ToolExecutionLedgerEntity>>> {
                            it.first.createdAt
                        }.thenBy { it.first.id },
                    )
                    (invalidGroups + validGroups.filterNot { it == selected }).forEach { (message, entries) ->
                        invalidateApprovalGroup(entries, message)
                        message?.let { database.messageDao().updatePendingApprovalToolIds(it.id, null) }
                    }
                    if (selected == null) {
                        database.sessionDao().updateToolApprovalState(
                            sessionId,
                            null,
                            LoopStatus.PAUSED.toSerializedName(),
                            clock(),
                        )
                        false
                    } else {
                        val (message, entries) = selected
                        val stableEntries = stableLedgerEntries(entries)
                        database.messageDao().updatePendingApprovalToolIds(
                            message.id,
                            json.encodeToString(stableEntries.map { it.toolCallId }.distinct()),
                        )
                        database.sessionDao().updateToolApprovalState(
                            sessionId,
                            recoveryApprovalRequest(stableEntries),
                            LoopStatus.WAITING_FOR_APPROVAL.toSerializedName(),
                            clock(),
                        )
                        true
                    }
                }
            }.getOrDefault(false)
            if (hasActive) activeSessions += sessionId
        }
        database.sessionDao().getWithToolApprovalState()
            .filterNot { it.id in activeSessions }
            .forEach { session ->
                runCatching {
                    database.withTransaction {
                        database.sessionDao().updateToolApprovalState(
                            session.id,
                            null,
                            LoopStatus.PAUSED.toSerializedName(),
                            clock(),
                        )
                    }
                }
            }
        return activeSessions.size
    }

    private fun recoveryApprovalRequest(entries: List<ToolExecutionLedgerEntity>): String =
        json.encodeToString(recoveryApprovalRequestModel(stableLedgerEntries(entries)))

    private fun recoveryApprovalRequestModel(entries: List<ToolExecutionLedgerEntity>) =
        ApprovalRequest(
            toolName = entries.firstOrNull()?.toolName,
            reason = "恢复未完成的工具审批，请确认后继续",
            type = "tool_approval",
        )

    private fun stableLedgerEntries(entries: List<ToolExecutionLedgerEntity>) =
        entries.sortedWith(compareBy<ToolExecutionLedgerEntity> { it.createdAt }.thenBy { it.toolCallId })

    private suspend fun invalidateApprovalGroup(
        entries: List<ToolExecutionLedgerEntity>,
        parent: com.promenar.nexara.data.local.db.entity.MessageEntity?,
    ): List<Message> {
        val terminalMessages = mutableListOf<Message>()
        stableLedgerEntries(entries).forEach { entry ->
            val key = ToolExecutionKey(entry.sessionId, entry.assistantMessageId, entry.toolCallId)
            if (parent == null || parent.sessionId != entry.sessionId) return@forEach
            val createdAt = clock()
            val message = parent.takeIf { it.role == "assistant" }?.let {
                terminalMessage(
                    ToolTerminalRequest(key, entry.toolName, it.thoughtSignature),
                    ToolLedgerState.FAILED,
                    "工具审批状态无效，已安全终止，请重新发起。",
                    createdAt,
                )
            }
            if (dao.invalidateAwaitingApproval(
                    entry.sessionId,
                    entry.assistantMessageId,
                    entry.toolCallId,
                    message?.id,
                    "工具审批布局校验失败",
                    createdAt,
                ) == 1
            ) {
                message?.let {
                    database.messageDao().insert(it.toEntity(entry.sessionId))
                    terminalMessages += it
                }
            }
        }
        return terminalMessages
    }

    private suspend fun terminalizeKeys(
        keys: Set<ToolExecutionKey>,
        state: ToolLedgerState,
    ): List<Message> = terminalizeWithResults(
        keys.mapNotNullTo(mutableSetOf()) { key ->
            dao.get(key.sessionId, key.assistantMessageId, key.toolCallId)?.let {
                ToolTerminalRequest(key, it.toolName)
            }
        },
        state,
    )

    private fun terminalDecision(state: ToolLedgerState): Triple<Set<ToolLedgerState>, String, String>? =
        when (state) {
            ToolLedgerState.REJECTED -> Triple(
                setOf(ToolLedgerState.PENDING_APPROVAL),
                "用户拒绝工具执行",
                "工具执行已拒绝。",
            )
            ToolLedgerState.CANCELLED -> Triple(
                setOf(ToolLedgerState.PENDING_APPROVAL, ToolLedgerState.APPROVED),
                "工具执行已取消",
                "工具执行已取消。",
            )
            ToolLedgerState.TIMED_OUT -> Triple(
                setOf(ToolLedgerState.PENDING_APPROVAL, ToolLedgerState.APPROVED),
                "工具审批已超时",
                "工具审批已超时。",
            )
            else -> null
        }

    private fun terminalMessage(
        request: ToolTerminalRequest,
        state: ToolLedgerState,
        content: String,
        createdAt: Long,
        images: String? = null,
    ) = Message(
        id = deterministicResultMessageId(request.key, state),
        role = MessageRole.TOOL,
        toolCallId = request.key.toolCallId,
        parentMessageId = request.key.assistantMessageId,
        name = request.toolName,
        content = content,
        thoughtSignature = request.thoughtSignature,
        images = images,
        createdAt = createdAt,
    )

    private fun deterministicResultMessageId(key: ToolExecutionKey, state: ToolLedgerState): String {
        val raw = "${key.sessionId}\u0000${key.assistantMessageId}\u0000${key.toolCallId}\u0000${state.name}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return "tool_result_" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 256
    }
}
