package com.promenar.nexara.data.repository

import android.content.SharedPreferences
import com.promenar.nexara.data.model.TokenUsage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal data class RolledBackSummaryUsageRecord(
    val messageId: String,
    val sessionId: String,
    val modelId: String,
    val usage: TokenUsage,
    val createdAt: Long,
)

/** 保存准备消息回滚前已发生的摘要费用，不保存提示词或回复正文。 */
class RolledBackSummaryUsageStore(
    private val preferences: SharedPreferences,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    internal suspend fun preserveBeforeDiscard(
        records: List<RolledBackSummaryUsageRecord>,
        discardMessages: suspend () -> Unit,
    ) = withContext(dispatcher) {
        processMutex.withLock {
            if (records.isNotEmpty()) {
                val updated = read().associateBy { it.messageId }.toMutableMap()
                // 每条消息携带累计摘要快照；重入时覆写，避免重复计费。
                records.forEach { updated[it.messageId] = it }
                val encoded = Json.encodeToString(ListSerializer(RolledBackSummaryUsageRecord.serializer()), updated.values.toList())
                check(preferences.edit().putString(KEY, encoded).commit()) { "无法保存回滚摘要用量统计" }
            }
            // 写盘失败或取消时保留原消息，费用仍可从消息中恢复。
            discardMessages()
        }
    }

    internal suspend fun <T> withRecords(
        block: suspend (List<RolledBackSummaryUsageRecord>) -> T,
    ): T = withContext(dispatcher) {
        // 与回滚共用锁，使 SQL 聚合和消息存在性检查不会跨越删除边界。
        processMutex.withLock { block(read()) }
    }

    internal suspend fun reset(clearMessageUsage: suspend () -> Unit) = withContext(dispatcher) {
        processMutex.withLock {
            clearMessageUsage()
            check(preferences.edit().remove(KEY).commit()) { "无法清除回滚摘要用量统计" }
        }
    }

    private fun read(): List<RolledBackSummaryUsageRecord> = preferences.getString(KEY, null)?.let {
        Json.decodeFromString<List<RolledBackSummaryUsageRecord>>(it)
    }.orEmpty()

    companion object {
        internal const val KEY = "rolled_back_summary_usage_v1"
        private val processMutex = Mutex()
    }
}
