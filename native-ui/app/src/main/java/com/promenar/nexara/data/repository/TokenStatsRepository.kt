package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.domain.repository.ITokenStatsRepository
import com.promenar.nexara.domain.repository.ModelTokenStats
import com.promenar.nexara.domain.repository.TokenUsageAggregate
import com.promenar.nexara.domain.repository.SessionTokenUsage
import com.promenar.nexara.domain.repository.DailyTokenStats

class TokenStatsRepository(
    private val messageDao: MessageDao
) : ITokenStatsRepository {

    override suspend fun getTotalUsage(): TokenUsageAggregate {
        val row = messageDao.getTotalTokenUsage()
        return TokenUsageAggregate(
            inputTokens = row.total_input,
            outputTokens = row.total_output
        )
    }

    override suspend fun getUsageByModel(): List<ModelTokenStats> {
        return messageDao.getTokenUsageByModel().map { row ->
            ModelTokenStats(
                modelId = row.model_id,
                usage = TokenUsageAggregate(
                    inputTokens = row.total_input,
                    outputTokens = row.total_output
                )
            )
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
        return messageDao.getDailyTokenTrend(sinceTimestamp).mapNotNull { row ->
            val day = row.day ?: return@mapNotNull null
            DailyTokenStats(
                day = day,
                inputTokens = row.total_input,
                outputTokens = row.total_output
            )
        }
    }

    override suspend fun resetStats() {
        messageDao.clearAllTokenData()
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
