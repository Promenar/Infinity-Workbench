package com.promenar.nexara.data.rag

import android.content.SharedPreferences
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class KgUsageRecord(
    val modelId: String,
    val usage: KgTokenUsage,
    val calls: Long = 1,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class KgDailyUsage(val day: String, val usage: KgTokenUsage)

data class KgUsageSnapshot(
    val records: List<KgUsageRecord> = emptyList(),
    val dailyUsage: List<KgDailyUsage> = emptyList(),
) {
    val usage: KgTokenUsage get() = records.fold(KgTokenUsage()) { sum, record -> sum + record.usage }
    val calls: Long get() = records.sumOf { it.calls }
}

interface KgUsageStore {
    suspend fun record(record: KgUsageRecord)
    suspend fun snapshot(): KgUsageSnapshot
    suspend fun clear()

    companion object {
        const val OTHER_MODEL_ID = "__kg_other_models__"
        val None = object : KgUsageStore {
            override suspend fun record(record: KgUsageRecord) = Unit
            override suspend fun snapshot() = KgUsageSnapshot()
            override suspend fun clear() = Unit
        }
    }
}

@Serializable
private data class KgUsageLedger(
    val models: List<KgUsageRecord> = emptyList(),
    val daily: List<KgDailyUsage> = emptyList(),
)

/** 仅持久化聚合 token 与调用数，不保存提示词、响应或逐请求记录。 */
class PreferencesKgUsageStore(
    private val preferences: SharedPreferences,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : KgUsageStore {
    private val mutex = Mutex()

    override suspend fun record(record: KgUsageRecord) = withContext(dispatcher) {
        mutex.withLock {
            val ledger = read()
            val modelId = record.modelId.take(512)
            val existing = ledger.models.firstOrNull { it.modelId == modelId }
            val updated = record.copy(
                modelId = modelId,
                usage = (existing?.usage ?: KgTokenUsage()) + record.usage,
                calls = (existing?.calls ?: 0) + record.calls,
            )
            val models = (ledger.models.filterNot { it.modelId == modelId } + updated).toMutableList()
            if (models.size > MAX_MODELS) {
                val evicted = models.filterNot { it.modelId == KgUsageStore.OTHER_MODEL_ID }.minBy { it.createdAt }
                models.remove(evicted)
                val other = models.firstOrNull { it.modelId == KgUsageStore.OTHER_MODEL_ID }
                models.removeAll { it.modelId == KgUsageStore.OTHER_MODEL_ID }
                models += KgUsageRecord(
                    KgUsageStore.OTHER_MODEL_ID, (other?.usage ?: KgTokenUsage()) + evicted.usage,
                    (other?.calls ?: 0) + evicted.calls, record.createdAt,
                )
                // 新建归档桶时也保持模型条目上限。
                if (models.size > MAX_MODELS) {
                    val next = models.filterNot { it.modelId == KgUsageStore.OTHER_MODEL_ID }.minBy { it.createdAt }
                    models.remove(next)
                    val archived = models.single { it.modelId == KgUsageStore.OTHER_MODEL_ID }
                    models.remove(archived)
                    models += archived.copy(usage = archived.usage + next.usage, calls = archived.calls + next.calls)
                }
            }
            val day = Instant.ofEpochMilli(record.createdAt).atZone(ZoneOffset.UTC).toLocalDate().toString()
            val daily = ledger.daily.associate { it.day to it.usage }.toMutableMap()
            daily[day] = (daily[day] ?: KgTokenUsage()) + record.usage
            write(KgUsageLedger(models, daily.entries.sortedByDescending { it.key }.take(MAX_DAYS).map { KgDailyUsage(it.key, it.value) }))
        }
    }

    override suspend fun snapshot(): KgUsageSnapshot = withContext(dispatcher) {
        mutex.withLock { read().let { KgUsageSnapshot(it.models, it.daily) } }
    }

    override suspend fun clear() = withContext(dispatcher) {
        mutex.withLock { check(preferences.edit().remove(KEY).commit()) { "无法清除知识图谱用量统计" } }
    }

    private fun read(): KgUsageLedger = preferences.getString(KEY, null)?.let {
        Json.decodeFromString<KgUsageLedger>(it)
    } ?: KgUsageLedger()

    private fun write(ledger: KgUsageLedger) {
        check(preferences.edit().putString(KEY, Json.encodeToString(KgUsageLedger.serializer(), ledger)).commit()) {
            "无法保存知识图谱用量统计"
        }
    }

    companion object {
        private const val KEY = "kg_usage_ledger_v1"
        internal const val MAX_MODELS = 256
        internal const val MAX_DAYS = 90
    }
}
