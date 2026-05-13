package com.promenar.nexara.domain.repository

data class TokenUsageAggregate(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val estimated: Boolean = false
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

data class ModelTokenStats(
    val modelId: String,
    val usage: TokenUsageAggregate
)

interface ITokenStatsRepository {
    suspend fun getTotalUsage(): TokenUsageAggregate
    suspend fun getUsageByModel(): List<ModelTokenStats>
    suspend fun getUsageBySession(sessionId: String): TokenUsageAggregate
    suspend fun resetStats()
}
