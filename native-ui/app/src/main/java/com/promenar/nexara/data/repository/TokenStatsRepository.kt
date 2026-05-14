package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.data.model.TokenUsage
import com.promenar.nexara.data.model.json
import com.promenar.nexara.domain.repository.ITokenStatsRepository
import com.promenar.nexara.domain.repository.ModelTokenStats
import com.promenar.nexara.domain.repository.TokenUsageAggregate
import com.promenar.nexara.domain.repository.SessionTokenUsage
import com.promenar.nexara.domain.repository.DailyTokenStats
import kotlinx.serialization.serializer

class TokenStatsRepository(
    private val messageDao: MessageDao
) : ITokenStatsRepository {

    private fun parseTokens(jsonStr: String?): TokenUsage? {
        if (jsonStr.isNullOrBlank()) return null
        return try {
            json.decodeFromString(serializer<TokenUsage>(), jsonStr)
        } catch (_: Exception) {
            null
        }
    }

    private fun aggregateFromEntities(
        entities: List<com.promenar.nexara.data.local.db.entity.MessageEntity>
    ): TokenUsageAggregate {
        var input = 0L
        var output = 0L
        var hasEstimated = false
        for (entity in entities) {
            val usage = parseTokens(entity.tokens) ?: continue
            input += usage.input
            output += usage.output
        }
        return TokenUsageAggregate(
            inputTokens = input,
            outputTokens = output,
            estimated = hasEstimated
        )
    }

    override suspend fun getTotalUsage(): TokenUsageAggregate {
        val entities = messageDao.getMessagesWithTokens()
        return aggregateFromEntities(entities)
    }

    override suspend fun getUsageByModel(): List<ModelTokenStats> {
        val entities = messageDao.getMessagesWithTokens()
        val grouped = mutableMapOf<String, TokenUsageAggregate>()
        for (entity in entities) {
            val usage = parseTokens(entity.tokens) ?: continue
            val modelId = entity.modelId ?: "unknown"
            val existing = grouped.getOrPut(modelId) { TokenUsageAggregate() }
            grouped[modelId] = existing.copy(
                inputTokens = existing.inputTokens + usage.input,
                outputTokens = existing.outputTokens + usage.output
            )
        }
        return grouped.map { (modelId, usage) ->
            ModelTokenStats(modelId = modelId, usage = usage)
        }.sortedByDescending { it.usage.totalTokens }
    }

    override suspend fun getUsageBySession(sessionId: String): TokenUsageAggregate {
        val entities = messageDao.getMessagesWithTokensBySession(sessionId)
        return aggregateFromEntities(entities)
    }

    override suspend fun getTopSessions(limit: Int): List<SessionTokenUsage> {
        val entities = messageDao.getMessagesWithTokens()
        val grouped = mutableMapOf<String, Pair<Long, Long>>()
        for (entity in entities) {
            val usage = parseTokens(entity.tokens) ?: continue
            val existing = grouped.getOrPut(entity.sessionId) { Pair(0L, 0L) }
            grouped[entity.sessionId] = Pair(existing.first + usage.input, existing.second + usage.output)
        }
        val sessionTitles = mutableMapOf<String, String?>()
        return grouped
            .map { (sessionId, pair) ->
                SessionTokenUsage(
                    sessionId = sessionId,
                    title = sessionTitles[sessionId],
                    inputTokens = pair.first,
                    outputTokens = pair.second
                )
            }
            .sortedByDescending { it.totalTokens }
            .take(limit)
    }

    override suspend fun getDailyTrend(days: Int): List<DailyTokenStats> {
        val sinceTimestamp = System.currentTimeMillis() - (days.toLong() * 24 * 60 * 60 * 1000)
        val entities = messageDao.getMessagesWithTokens()
        val grouped = mutableMapOf<String, Pair<Long, Long>>()
        for (entity in entities) {
            if (entity.createdAt < sinceTimestamp) continue
            val usage = parseTokens(entity.tokens) ?: continue
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(entity.createdAt)
            val existing = grouped.getOrPut(day) { Pair(0L, 0L) }
            grouped[day] = Pair(existing.first + usage.input, existing.second + usage.output)
        }
        return grouped.map { (day, pair) ->
            DailyTokenStats(day = day, inputTokens = pair.first, outputTokens = pair.second)
        }.sortedBy { it.day }
    }

    override suspend fun resetStats() {
        messageDao.clearAllTokenData()
    }
}
