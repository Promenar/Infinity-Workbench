package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.domain.repository.ITokenStatsRepository
import com.promenar.nexara.domain.repository.ModelTokenStats
import com.promenar.nexara.domain.repository.TokenUsageAggregate
import com.promenar.nexara.domain.repository.SessionTokenUsage
import com.promenar.nexara.domain.repository.DailyTokenStats
import com.promenar.nexara.domain.repository.CategoryTokenStats
import com.promenar.nexara.data.rag.KgUsageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TokenStatsRepository(
    private val messageDao: MessageDao,
    private val kgUsageStore: KgUsageStore = KgUsageStore.None,
) : ITokenStatsRepository {

    override suspend fun getTotalUsage(): TokenUsageAggregate {
        val row = messageDao.getTotalTokenUsage()
        val kg = kgUsageStore.snapshot().usage
        return TokenUsageAggregate(
            inputTokens = row.total_input + kg.inputTokens,
            outputTokens = row.total_output + kg.outputTokens,
            estimated = kg.estimated,
        )
    }

    override suspend fun getUsageByModel(): List<ModelTokenStats> {
        val chat = messageDao.getTokenUsageByModel().map { row ->
            ModelTokenStats(
                modelId = row.model_id,
                usage = TokenUsageAggregate(
                    inputTokens = row.total_input,
                    outputTokens = row.total_output
                )
            )
        }
        val kg = kgUsageStore.snapshot().records.map { record ->
            ModelTokenStats(record.modelId, TokenUsageAggregate(record.usage.inputTokens, record.usage.outputTokens, record.usage.estimated))
        }
        return (chat + kg).groupBy { it.modelId }.map { (modelId, entries) ->
            ModelTokenStats(modelId, TokenUsageAggregate(
                entries.sumOf { it.usage.inputTokens }, entries.sumOf { it.usage.outputTokens }, entries.any { it.usage.estimated },
            ))
        }
    }

    override suspend fun getUsageBySession(sessionId: String): TokenUsageAggregate {
        val entities = messageDao.getMessagesWithTokensBySession(sessionId)
        var input = 0L
        var output = 0L
        for (entity in entities) {
            val usage = parseTokens(entity.tokens) ?: continue
            input += usage.input
            output += usage.output
        }
        return TokenUsageAggregate(inputTokens = input, outputTokens = output)
    }

    override suspend fun getTopSessions(limit: Int): List<SessionTokenUsage> {
        return messageDao.getSessionTokenRanking(limit).map { row ->
            SessionTokenUsage(
                sessionId = row.session_id,
                title = row.title,
                inputTokens = row.total_input,
                outputTokens = row.total_output
            )
        }
    }

    override suspend fun getDailyTrend(days: Int): List<DailyTokenStats> {
        val sinceTimestamp = System.currentTimeMillis() - (days.toLong() * 24 * 60 * 60 * 1000)
        val chat = messageDao.getDailyTokenTrend(sinceTimestamp).mapNotNull { row ->
            val day = row.day ?: return@mapNotNull null
            DailyTokenStats(
                day = day,
                inputTokens = row.total_input,
                outputTokens = row.total_output
            )
        }
        val firstDay = java.time.Instant.ofEpochMilli(sinceTimestamp).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        val kg = kgUsageStore.snapshot().dailyUsage.filter { it.day >= firstDay }.map {
            DailyTokenStats(it.day, it.usage.inputTokens, it.usage.outputTokens)
        }
        return (chat + kg).groupBy { it.day }.map { (day, records) ->
            DailyTokenStats(day, records.sumOf { it.inputTokens }, records.sumOf { it.outputTokens })
        }.sortedBy { it.day }
    }

    override suspend fun getUsageByCategory(): List<CategoryTokenStats> = withContext(Dispatchers.IO) {
        val chat = messageDao.getTotalTokenUsage()
        val kg = kgUsageStore.snapshot()
        listOf(
            CategoryTokenStats("chat", TokenUsageAggregate(chat.total_input, chat.total_output)),
            CategoryTokenStats("kg", TokenUsageAggregate(kg.usage.inputTokens, kg.usage.outputTokens, kg.usage.estimated), kg.calls),
        )
    }

    override suspend fun resetStats() {
        messageDao.clearAllTokenData()
        kgUsageStore.clear()
    }

    // 仅 getUsageBySession 仍需解析 JSON（该路径无法用 SQL 替代，因为 session 级查询已由 DAO 直接处理）
    private fun parseTokens(jsonStr: String?): com.promenar.nexara.data.model.TokenUsage? {
        if (jsonStr.isNullOrBlank()) return null
        return try {
            com.promenar.nexara.data.model.json.decodeFromString(
                kotlinx.serialization.serializer(),
                jsonStr
            )
        } catch (_: Exception) {
            null
        }
    }
}
