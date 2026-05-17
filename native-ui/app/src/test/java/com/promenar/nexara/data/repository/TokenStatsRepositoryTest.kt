package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.DailyTokenRow
import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.data.local.db.dao.ModelTokenRow
import com.promenar.nexara.data.local.db.dao.SessionTokenRow
import com.promenar.nexara.data.local.db.dao.TokenTotalRow
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TokenStatsRepositoryTest {

    private val messageDao: MessageDao = mockk()
    private val repository = TokenStatsRepository(messageDao)

    @Test
    fun `getTotalUsage returns zero when no messages`() = runTest {
        coEvery { messageDao.getTotalTokenUsage() } returns TokenTotalRow(0, 0)

        val result = repository.getTotalUsage()

        assertThat(result.inputTokens).isEqualTo(0)
        assertThat(result.outputTokens).isEqualTo(0)
        assertThat(result.totalTokens).isEqualTo(0)
    }

    @Test
    fun `getTotalUsage aggregates from SQL`() = runTest {
        coEvery { messageDao.getTotalTokenUsage() } returns TokenTotalRow(300, 150)

        val result = repository.getTotalUsage()

        assertThat(result.inputTokens).isEqualTo(300)
        assertThat(result.outputTokens).isEqualTo(150)
        assertThat(result.totalTokens).isEqualTo(450)
    }

    @Test
    fun `getUsageByModel groups by modelId`() = runTest {
        coEvery { messageDao.getTokenUsageByModel() } returns listOf(
            ModelTokenRow("gpt-4", 300, 150),
            ModelTokenRow("claude-3", 300, 150)
        )

        val result = repository.getUsageByModel()

        assertThat(result).hasSize(2)
        val gpt4 = result.find { it.modelId == "gpt-4" }!!
        assertThat(gpt4.usage.inputTokens).isEqualTo(300)
        assertThat(gpt4.usage.outputTokens).isEqualTo(150)

        val claude = result.find { it.modelId == "claude-3" }!!
        assertThat(claude.usage.inputTokens).isEqualTo(300)
        assertThat(claude.usage.outputTokens).isEqualTo(150)
    }

    @Test
    fun `getUsageByModel uses unknown for null modelId`() = runTest {
        coEvery { messageDao.getTokenUsageByModel() } returns listOf(
            ModelTokenRow("unknown", 50, 25)
        )

        val result = repository.getUsageByModel()

        assertThat(result).hasSize(1)
        assertThat(result[0].modelId).isEqualTo("unknown")
    }

    @Test
    fun `getUsageBySession delegates to session-specific query`() = runTest {
        coEvery { messageDao.getMessagesWithTokensBySession("s1") } returns listOf(
            messageEntity(id = "m1", sessionId = "s1", tokens = """{"input":100,"output":50,"total":150}""")
        )

        val result = repository.getUsageBySession("s1")

        assertThat(result.inputTokens).isEqualTo(100)
        assertThat(result.outputTokens).isEqualTo(50)
        coVerify { messageDao.getMessagesWithTokensBySession("s1") }
    }

    @Test
    fun `getTopSessions maps DAO rows`() = runTest {
        coEvery { messageDao.getSessionTokenRanking(10) } returns listOf(
            SessionTokenRow("s1", "My Chat", 500, 300),
            SessionTokenRow("s2", null, 200, 100)
        )

        val result = repository.getTopSessions(10)

        assertThat(result).hasSize(2)
        assertThat(result[0].title).isEqualTo("My Chat")
        assertThat(result[0].inputTokens).isEqualTo(500)
        assertThat(result[1].title).isNull()
        assertThat(result[1].sessionId).isEqualTo("s2")
    }

    @Test
    fun `getDailyTrend maps DAO rows and filters null days`() = runTest {
        coEvery { messageDao.getDailyTokenTrend(any()) } returns listOf(
            DailyTokenRow("2026-05-15", 100, 50),
            DailyTokenRow("2026-05-16", 200, 80),
            DailyTokenRow(null, 10, 5) // should be filtered
        )

        val result = repository.getDailyTrend(7)

        assertThat(result).hasSize(2)
        assertThat(result[0].day).isEqualTo("2026-05-15")
        assertThat(result[1].day).isEqualTo("2026-05-16")
    }

    @Test
    fun `resetStats delegates to dao clearAllTokenData`() = runTest {
        coEvery { messageDao.clearAllTokenData() } returns Unit

        repository.resetStats()

        coVerify { messageDao.clearAllTokenData() }
    }

    @Test
    fun `getUsageBySession handles empty tokens`() = runTest {
        coEvery { messageDao.getMessagesWithTokensBySession("s1") } returns listOf(
            messageEntity(id = "m1", sessionId = "s1", tokens = null)
        )

        val result = repository.getUsageBySession("s1")

        assertThat(result.inputTokens).isEqualTo(0)
        assertThat(result.outputTokens).isEqualTo(0)
    }

    private fun messageEntity(
        id: String = "m1",
        sessionId: String = "s1",
        modelId: String? = "gpt-4",
        tokens: String? = null
    ) = MessageEntity(
        id = id,
        sessionId = sessionId,
        role = "assistant",
        content = "test",
        modelId = modelId,
        tokens = tokens,
        createdAt = System.currentTimeMillis()
    )
}
