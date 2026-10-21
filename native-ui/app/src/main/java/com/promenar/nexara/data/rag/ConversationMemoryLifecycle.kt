package com.promenar.nexara.data.rag

import androidx.room.withTransaction
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.model.MessageRole
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
    suspend fun onMessageEdited(sessionId: String, messageId: String)
    suspend fun purgeExpired()
}

fun interface MemoryTurnWriter {
    suspend fun write(
        sessionId: String,
        userContent: String,
        assistantContent: String,
        userMessageId: String,
        assistantMessageId: String,
    )
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

    override suspend fun onMessageEdited(sessionId: String, messageId: String) = mutex.withLock { withContext(Dispatchers.IO) {
        val database = databaseProvider()
        val message = database.messageDao().getById(messageId) ?: return@withContext
        if (message.sessionId != sessionId) return@withContext
        val vectors = database.vectorDao()
        database.withTransaction { invalidateSessionJit(database, sessionId) }
        when (MessageRole.fromSerializedName(message.role)) {
            // 用户消息变化后该轮问答已不成立；随后的重新生成会写入新的一轮。
            MessageRole.USER -> {
                val ids = vectors.getMemoryByUserMessage(sessionId, messageId).map { it.id }
                if (ids.isNotEmpty()) vectors.deleteByIds(ids)
            }
            MessageRole.ASSISTANT -> {
                val previous = vectors.getMemoryByAssistantMessage(sessionId, messageId)
                if (previous.isEmpty()) return@withContext
                try {
                    val userMessage = previous.firstNotNullOfOrNull { it.startMessageId }
                        ?.let { database.messageDao().getById(it) }
                    if (userMessage != null && userMessage.content.isNotBlank() && message.content.isNotBlank()) {
                        turnWriter.write(sessionId, userMessage.content, message.content, userMessage.id, message.id)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    NexaraLogger.logError("[ConversationMemory] 编辑后重写记忆失败", error)
                } finally {
                    // 旧正文的记忆无论重写是否成功都不再代表这轮回复。
                    withContext(NonCancellable) { vectors.deleteByIds(previous.map { it.id }) }
                }
            }
            else -> Unit
        }
    } }

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
