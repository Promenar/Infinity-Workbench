package com.promenar.nexara.data.rag

import androidx.room.withTransaction
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.TurnMemoryState
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.hours

/**
 * 聊天记录变化时维护对话记忆与即时图谱证据。
 *
 * 记忆的可见性由所属消息是否存在决定（见 VectorDao 的 MEMORY_VISIBLE），删除、丢弃、回退、清空
 * 无需逐行删除；这里负责撤销窗口标记、即时证据失效、编辑后的重写和窗口过后的物理删除。
 */
interface ConversationMemoryLifecycle {
    suspend fun onMessagesRemoved(sessionId: String)
    suspend fun onMessagesRestored(sessionId: String)

    /**
     * 返回记忆随编辑改变的各轮新状态（键为该轮最后一条助手消息）：编辑助手消息时重写该轮，
     * 编辑用户消息时该轮记忆被移除。未改变记忆时为空。
     */
    suspend fun onMessageEdited(sessionId: String, messageId: String): Map<String, TurnMemoryState>
    suspend fun purgeExpired()

    /** 只从记忆中移除一轮（以该轮最后一条助手消息定位），消息保留。返回删除的片段数。 */
    suspend fun removeTurn(sessionId: String, assistantMessageId: String): Int

    /** 按当前消息内容重新写入一轮记忆，替换该轮已有片段。返回写入的片段数。 */
    suspend fun rewriteTurn(sessionId: String, assistantMessageId: String): Int

    /** 一轮当前在记忆中的片段数。 */
    suspend fun turnChunkCount(sessionId: String, assistantMessageId: String): Int
}

fun interface MemoryTurnWriter {
    /** 返回写入的片段数。 */
    suspend fun write(
        sessionId: String,
        userContent: String,
        assistantContent: String,
        userMessageId: String,
        assistantMessageId: String,
    ): Int
}

class RoomConversationMemoryLifecycle(
    private val databaseProvider: () -> NexaraDatabase,
    private val turnWriter: MemoryTurnWriter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val undoWindowMillis: Long = 24.hours.inWholeMilliseconds,
) : ConversationMemoryLifecycle {
    // 编辑重写先读旧行再写新行，并发执行会留下重复记忆；所有操作串行。
    private val mutex = Mutex()

    override suspend fun onMessagesRemoved(sessionId: String) = mutex.withLock { withContext(Dispatchers.IO) {
        val database = databaseProvider()
        database.withTransaction {
            database.vectorDao().markOrphanMemory(clock())
            invalidateSessionJit(database, sessionId)
        }
        Unit
    } }

    override suspend fun onMessagesRestored(sessionId: String) = mutex.withLock { withContext(Dispatchers.IO) {
        databaseProvider().vectorDao().reviveRestoredMemory()
        Unit
    } }

    override suspend fun onMessageEdited(sessionId: String, messageId: String): Map<String, TurnMemoryState> = mutex.withLock { withContext(Dispatchers.IO) {
        val database = databaseProvider()
        val message = database.messageDao().getById(messageId) ?: return@withContext emptyMap()
        if (message.sessionId != sessionId) return@withContext emptyMap()
        val vectors = database.vectorDao()
        database.withTransaction { invalidateSessionJit(database, sessionId) }
        when (MessageRole.fromSerializedName(message.role)) {
            // 用户消息变化后该轮问答已不成立；随后的重新生成会写入新的一轮。
            MessageRole.USER -> {
                val previous = vectors.getMemoryByUserMessage(sessionId, messageId)
                if (previous.isNotEmpty()) vectors.deleteByIds(previous.map { it.id })
                previous.mapNotNull { it.endMessageId }.distinct()
                    .associateWith { TurnMemoryState(TurnMemoryState.Status.REMOVED, updatedAt = clock()) }
            }
            MessageRole.ASSISTANT -> {
                val previous = vectors.getMemoryByAssistantMessage(sessionId, messageId)
                if (previous.isEmpty()) return@withContext emptyMap()
                val written = try {
                    val userMessage = previous.firstNotNullOfOrNull { it.startMessageId }
                        ?.let { database.messageDao().getById(it) }
                    if (userMessage != null && userMessage.content.isNotBlank() && message.content.isNotBlank()) {
                        turnWriter.write(sessionId, userMessage.content, message.content, userMessage.id, message.id)
                    } else 0
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    NexaraLogger.logError("[ConversationMemory] 编辑后重写记忆失败", error)
                    null
                } finally {
                    // 旧正文的记忆无论重写是否成功都不再代表这轮回复。
                    withContext(NonCancellable) { vectors.deleteByIds(previous.map { it.id }) }
                }
                mapOf(messageId to turnState(written))
            }
            else -> emptyMap()
        }
    } }

    override suspend fun removeTurn(sessionId: String, assistantMessageId: String): Int = mutex.withLock { withContext(Dispatchers.IO) {
        val database = databaseProvider()
        val ids = database.vectorDao().getMemoryByAssistantMessage(sessionId, assistantMessageId).map { it.id }
        database.withTransaction {
            if (ids.isNotEmpty()) database.vectorDao().deleteByIds(ids)
            invalidateSessionJit(database, sessionId)
        }
        ids.size
    } }

    override suspend fun rewriteTurn(sessionId: String, assistantMessageId: String): Int = mutex.withLock { withContext(Dispatchers.IO) {
        val database = databaseProvider()
        val message = database.messageDao().getById(assistantMessageId)
            ?.takeIf { it.sessionId == sessionId && MessageRole.fromSerializedName(it.role) == MessageRole.ASSISTANT }
            ?: error("助手消息不存在")
        // 助手消息的 parent 是本轮用户消息（见生成链路），缺失时该轮无法组成问答。
        val userMessage = message.parentMessageId?.let { database.messageDao().getById(it) }
            ?.takeIf { it.sessionId == sessionId && it.content.isNotBlank() }
            ?: error("本轮用户消息不存在")
        require(message.content.isNotBlank()) { "本轮回复正文为空" }
        val vectors = database.vectorDao()
        val previous = vectors.getMemoryByAssistantMessage(sessionId, assistantMessageId).map { it.id }
        val written = turnWriter.write(sessionId, userMessage.content, message.content, userMessage.id, message.id)
        if (previous.isNotEmpty()) withContext(NonCancellable) { vectors.deleteByIds(previous) }
        database.withTransaction { invalidateSessionJit(database, sessionId) }
        written
    } }

    override suspend fun turnChunkCount(sessionId: String, assistantMessageId: String): Int = mutex.withLock { withContext(Dispatchers.IO) {
        databaseProvider().vectorDao().getMemoryByAssistantMessage(sessionId, assistantMessageId).size
    } }

    /** 编辑后重写的结果：写入失败为 null，记为失败以便重试；正文为空等无可写内容时为移除。 */
    private fun turnState(written: Int?) = TurnMemoryState(
        status = when {
            written == null -> TurnMemoryState.Status.FAILED
            written > 0 -> TurnMemoryState.Status.STORED
            else -> TurnMemoryState.Status.REMOVED
        },
        chunkCount = written ?: 0,
        updatedAt = clock(),
    )

    override suspend fun purgeExpired() = mutex.withLock { withContext(Dispatchers.IO) {
        val vectors = databaseProvider().vectorDao()
        val now = clock()
        vectors.reviveRestoredMemory()
        vectors.markOrphanMemory(now)
        vectors.purgeOrphanMemory(now - undoWindowMillis)
        Unit
    } }

    private suspend fun invalidateSessionJit(database: NexaraDatabase, sessionId: String) {
        val (edges, nodes) = database.kgEvidenceDao().clearSessionMemoryJit(sessionId)
        // 缓存键以检索正文计算，不含会话维度；与删除会话一致，整体失效以免命中含已删内容的结果。
        database.kgJitCacheDao().deleteAll()
        if (edges > 0 || nodes > 0) {
            NexaraLogger.log("[ConversationMemory] session=$sessionId 回收即时图谱关系 $edges、实体 $nodes")
        }
    }
}
