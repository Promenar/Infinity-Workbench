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

data class SessionTokenUsage(
    val sessionId: String,
    val title: String?,
    val inputTokens: Long,
    val outputTokens: Long
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

data class DailyTokenStats(
    val day: String,
    val inputTokens: Long,
    val outputTokens: Long
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

interface ITokenStatsRepository {
    suspend fun getTotalUsage(): TokenUsageAggregate
    suspend fun getUsageByModel(): List<ModelTokenStats>
    suspend fun getUsageBySession(sessionId: String): TokenUsageAggregate
    suspend fun getTopSessions(limit: Int): List<SessionTokenUsage>
    suspend fun getDailyTrend(days: Int): List<DailyTokenStats>
    suspend fun resetStats()
}
