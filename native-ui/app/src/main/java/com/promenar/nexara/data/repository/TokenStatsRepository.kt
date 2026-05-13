package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.data.model.TokenUsage
import com.promenar.nexara.data.model.json
import com.promenar.nexara.domain.repository.ITokenStatsRepository
import com.promenar.nexara.domain.repository.ModelTokenStats
import com.promenar.nexara.domain.repository.TokenUsageAggregate
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

    override suspend fun resetStats() {
        messageDao.clearAllTokenData()
    }
}
