package com.promenar.nexara.data.branch

import androidx.room.withTransaction
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.model.*
import com.promenar.nexara.domain.branch.*
import com.promenar.nexara.domain.trigger.*
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.MessageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class SessionMessageOutbox(val targetSessionId: String, val entry: SessionMessageEntry, val wrapUp: Boolean = false, val sequence: Int = 0, val sourceSubagentId: String? = null, val sourceTitle: String = "")

/** 发送副本就是持久 outbox，回执独立保存；接收端按发送消息 id 去重。 */
class SessionMessageStore(
    private val database: NexaraDatabase,
    private val scope: CoroutineScope,
    private val dispatcher: () -> SystemTurnDispatcher,
    private val store: ChatStore,
    private val messages: MessageManager,
    private val pausedText: () -> String,
) {
    private val json = Json { encodeDefaults = true }
    private val lane = Mutex()
    private val activeLock = Any()
    private val active = mutableSetOf<String>()
    suspend fun send(sourceId: String, targetId: String, entry: SessionMessageEntry, wrapUp: Boolean, sourceSubagentId: String? = null, sourceTitle: String? = null) = withContext(Dispatchers.IO) {
        lane.withLock {
            val prepared = database.withTransaction {
                val source = checkNotNull(database.sessionDao().getById(sourceId)) { "未找到该会话或子代理" }
                val target = checkNotNull(database.sessionDao().getById(targetId)) { "未找到该会话或子代理" }
                val previous = database.messageDao().getBySession(sourceId).filter { it.name == SENT_MESSAGE_NAME }
                    .mapNotNull { runCatching { json.decodeFromString<SessionMessageOutbox>(it.content).sequence }.getOrNull() }.maxOrNull() ?: 0
                val sequence = Math.addExact(previous, 1)
                val message = Message(id = "session_mail_${UUID.randomUUID()}", role = MessageRole.SYSTEM,
                    name = SENT_MESSAGE_NAME, content = json.encodeToString(SessionMessageOutbox(targetId, entry, wrapUp, sequence, sourceSubagentId, sourceTitle ?: source.title)))
                database.messageDao().insert(message.toEntity(sourceId))
                Triple(source, target, message)
            }
            if (sourceSubagentId == null && store.getSession(sourceId) != null) messages.mirrorPersistedMessage(sourceId, prepared.third)
            enqueue(prepared.first, prepared.second, prepared.third)
        }
    }
    private fun enqueue(source: com.promenar.nexara.data.local.db.entity.SessionEntity,
                        target: com.promenar.nexara.data.local.db.entity.SessionEntity, message: Message) {
        if (!synchronized(activeLock) { active.add(message.id) }) return
        // 在发送 lane 内同步入桥，直到第一次等待回执才让出；不能把整段等待放进 lane。
        scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                val envelope = runCatching { json.decodeFromString<SessionMessageOutbox>(message.content) }.getOrNull() ?: return@launch
                if (source.agentId != target.agentId) return@launch
                val received = envelope.entry.copy(direction = SessionMessageEntry.Direction.RECEIVED,
                    peerId = envelope.sourceSubagentId ?: source.id,
                    peerTitle = envelope.sourceTitle.ifBlank { source.title },
                    peerRelation = when {
                        envelope.sourceSubagentId != null -> SessionMessageEntry.PeerRelation.SUBAGENT
                        source.parentSessionId == target.id -> SessionMessageEntry.PeerRelation.BRANCH
                        else -> SessionMessageEntry.PeerRelation.PARENT
                    })
                val body = json.encodeToString(received) + if (envelope.wrapUp) "\n请尽快收尾，交回阶段结论与未完成事项。" else ""
                val request = SystemTurnRequest(target.id, SystemTurnKind.SESSION_MESSAGE,
                    sourceId = received.peerId, title = received.peerTitle, body = body,
                    sequence = envelope.sequence, deliveryIds = listOf(message.id))
                var retryMillis = 250L
                while (currentCoroutineContext().isActive) {
                    try {
                        val outcome = dispatcher().request(request)
                        if (outcome is SystemTurnOutcome.Completed || outcome is SystemTurnOutcome.NeedsUser ||
                            outcome is SystemTurnOutcome.Skipped && outcome.reason == SystemTurnSkipReason.SESSION_MISSING) {
                            database.withTransaction {
                                if (database.sessionDao().getById(source.id) != null && database.messageDao().getById(message.id)?.sessionId == source.id) {
                                    database.messageDao().insert(Message(id = "session_ack_${message.id}", role = MessageRole.SYSTEM,
                                        name = DELIVERY_MESSAGE_NAME, content = message.id).toEntity(source.id))
                                }
                            }
                            return@launch
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        com.promenar.nexara.utils.NexaraLogger.log("[SessionMessage] 送达或回执持久化失败，将重试：${failure::class.java.simpleName}")
                    }
                    delay(retryMillis)
                    retryMillis = (retryMillis * 2).coerceAtMost(30_000)
                    val currentSource = database.sessionDao().getById(source.id) ?: return@launch
                    val currentTarget = database.sessionDao().getById(target.id) ?: return@launch
                    if (currentSource.agentId != source.agentId || currentTarget.agentId != source.agentId) return@launch
                }
            } finally { synchronized(activeLock) { active.remove(message.id) } }
        }
    }
    suspend fun recover() = withContext(Dispatchers.IO) {
        lane.withLock {
            database.sessionDao().getAll().forEach { session ->
                val rows = database.messageDao().getBySession(session.id)
                val confirmed = rows.filter { it.name == DELIVERY_MESSAGE_NAME }.mapTo(hashSetOf()) { it.content }
                rows.filter { it.name == SENT_MESSAGE_NAME && it.id !in confirmed }
                    .sortedBy { runCatching { json.decodeFromString<SessionMessageOutbox>(it.content).sequence }.getOrDefault(Int.MAX_VALUE) }.forEach envelopeLoop@ { row ->
                    val envelope = runCatching { json.decodeFromString<SessionMessageOutbox>(row.content) }.getOrNull() ?: return@envelopeLoop
                    database.sessionDao().getById(envelope.targetSessionId)?.let { enqueue(session, it, row.toDomain()) }
                }
            }
        }
    }
    suspend fun recordSubagentSend(sourceId: String, runId: String, title: String, text: String) = withContext(Dispatchers.IO) {
        val entry = SessionMessageEntry(SessionMessageEntry.Direction.SENT, runId, SessionMessageEntry.PeerRelation.SUBAGENT, title, text, System.currentTimeMillis())
        val message = Message("session_record_${UUID.randomUUID()}", MessageRole.SYSTEM, json.encodeToString(entry), name = RECORD_MESSAGE_NAME)
        database.messageDao().insert(message.toEntity(sourceId))
        if (store.getSession(sourceId) != null) messages.mirrorPersistedMessage(sourceId, message)
    }
    /** 仅空闲时的新消息轮计数，步骤插入不计；同一持久消息的恢复不二次累加。 */
    suspend fun admitWake(sessionId: String, userId: String, kind: SystemTurnKind): Boolean = withContext(Dispatchers.IO) {
        if (kind != SystemTurnKind.SESSION_MESSAGE) return@withContext true
        var marker: Message? = null
        val allowed = database.withTransaction {
            val session = database.sessionDao().getById(sessionId) ?: return@withTransaction false
            val existing = database.messageDao().getById("session_wake_$userId")
            if (existing != null) return@withTransaction existing.content == "admitted" && !session.sessionAutoProcessingPaused
            val canWake = session.sessionAutoWakeCount < BranchLimits.MAX_AUTO_WAKES && !session.sessionAutoProcessingPaused
            database.sessionDao().updateRow(session.copy(sessionAutoWakeCount = if (canWake) session.sessionAutoWakeCount + 1 else session.sessionAutoWakeCount,
                sessionAutoProcessingPaused = !canWake))
            database.messageDao().insert(Message(id = "session_wake_$userId", role = MessageRole.SYSTEM,
                name = WAKE_MESSAGE_NAME, content = if (canWake) "admitted" else "paused").toEntity(sessionId))
            if (!canWake) {
                marker = Message(id = "session_pause_$userId", role = MessageRole.SYSTEM, name = PAUSED_MESSAGE_NAME, content = pausedText())
                database.messageDao().insert(marker!!.toEntity(sessionId))
            }
            canWake
        }
        marker?.let { if (store.getSession(sessionId) != null) messages.mirrorPersistedMessage(sessionId, it) }
        database.sessionDao().getById(sessionId)?.let { persisted ->
            store.updateSession(sessionId) { it.copy(sessionAutoWakeCount = persisted.sessionAutoWakeCount,
                sessionAutoProcessingPaused = persisted.sessionAutoProcessingPaused) }
        }
        allowed
    }
    suspend fun resetForUserInput(sessionId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val session = database.sessionDao().getById(sessionId) ?: return@withTransaction
            if (session.sessionAutoWakeCount != 0 || session.sessionAutoProcessingPaused)
                database.sessionDao().updateRow(session.copy(sessionAutoWakeCount = 0, sessionAutoProcessingPaused = false))
        }
        store.updateSession(sessionId) { it.copy(sessionAutoWakeCount = 0, sessionAutoProcessingPaused = false) }
    }
    companion object {
        const val SENT_MESSAGE_NAME = "session_message_sent"
        const val RECORD_MESSAGE_NAME = "session_message_record"
        const val DELIVERY_MESSAGE_NAME = "session_message_delivery"
        const val WAKE_MESSAGE_NAME = "session_message_wake"
        const val PAUSED_MESSAGE_NAME = "session_auto_processing_paused"
        fun isInternal(name: String?) = name in setOf(DELIVERY_MESSAGE_NAME, WAKE_MESSAGE_NAME, BranchWorktreeAuthorization.MESSAGE_NAME)
        /**
         * 只在会话记录里显示的条目：发送副本、发给子代理的记录与自动处理暂停标记。它们以 SYSTEM 角色写在工具调用与结果之间，
         * 不能作为用户轮次边界，也不进入模型历史（发送内容已在 `session_send` 调用参数中）。
         */
        fun isTimelineRecord(name: String?) = name in setOf(SENT_MESSAGE_NAME, RECORD_MESSAGE_NAME, PAUSED_MESSAGE_NAME)
    }
}
