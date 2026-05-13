package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.MessageDao
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

    @Test
    fun `getTotalUsage returns zero when no messages with tokens`() = runTest {
        coEvery { messageDao.getMessagesWithTokens() } returns emptyList()

        val result = repository.getTotalUsage()

        assertThat(result.inputTokens).isEqualTo(0)
        assertThat(result.outputTokens).isEqualTo(0)
        assertThat(result.totalTokens).isEqualTo(0)
    }

    @Test
    fun `getTotalUsage aggregates input and output tokens`() = runTest {
        coEvery { messageDao.getMessagesWithTokens() } returns listOf(
            messageEntity(id = "m1", tokens = """{"input":100,"output":50,"total":150}"""),
            messageEntity(id = "m2", tokens = """{"input":200,"output":100,"total":300}""")
        )

        val result = repository.getTotalUsage()

        assertThat(result.inputTokens).isEqualTo(300)
        assertThat(result.outputTokens).isEqualTo(150)
        assertThat(result.totalTokens).isEqualTo(450)
    }

    @Test
    fun `getTotalUsage skips messages with invalid token JSON`() = runTest {
        coEvery { messageDao.getMessagesWithTokens() } returns listOf(
            messageEntity(id = "m1", tokens = """{"input":100,"output":50,"total":150}"""),
            messageEntity(id = "m2", tokens = "invalid_json"),
            messageEntity(id = "m3", tokens = null)
        )

        val result = repository.getTotalUsage()

        assertThat(result.inputTokens).isEqualTo(100)
        assertThat(result.outputTokens).isEqualTo(50)
    }

    @Test
    fun `getUsageByModel groups by modelId`() = runTest {
        coEvery { messageDao.getMessagesWithTokens() } returns listOf(
            messageEntity(id = "m1", modelId = "gpt-4", tokens = """{"input":100,"output":50,"total":150}"""),
            messageEntity(id = "m2", modelId = "gpt-4", tokens = """{"input":200,"output":100,"total":300}"""),
            messageEntity(id = "m3", modelId = "claude-3", tokens = """{"input":300,"output":150,"total":450}""")
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
    fun `getUsageByModel sorts by total tokens descending`() = runTest {
        coEvery { messageDao.getMessagesWithTokens() } returns listOf(
            messageEntity(id = "m1", modelId = "small", tokens = """{"input":10,"output":5,"total":15}"""),
            messageEntity(id = "m2", modelId = "large", tokens = """{"input":1000,"output":500,"total":1500}""")
        )

        val result = repository.getUsageByModel()

        assertThat(result[0].modelId).isEqualTo("large")
        assertThat(result[1].modelId).isEqualTo("small")
    }

    @Test
    fun `getUsageByModel uses unknown for null modelId`() = runTest {
        coEvery { messageDao.getMessagesWithTokens() } returns listOf(
            messageEntity(id = "m1", modelId = null, tokens = """{"input":50,"output":25,"total":75}""")
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
    fun `resetStats delegates to dao clearAllTokenData`() = runTest {
        coEvery { messageDao.clearAllTokenData() } returns Unit

        repository.resetStats()

        coVerify { messageDao.clearAllTokenData() }
    }

    @Test
    fun `getTotalUsage handles default TokenUsage values`() = runTest {
        coEvery { messageDao.getMessagesWithTokens() } returns listOf(
            messageEntity(id = "m1", tokens = """{"input":0,"output":0,"total":0}""")
        )

        val result = repository.getTotalUsage()

        assertThat(result.inputTokens).isEqualTo(0)
        assertThat(result.outputTokens).isEqualTo(0)
    }

    @Test
    fun `getUsageByModel handles partial TokenUsage JSON`() = runTest {
        coEvery { messageDao.getMessagesWithTokens() } returns listOf(
            messageEntity(id = "m1", tokens = """{}""")
        )

        val result = repository.getUsageByModel()

        assertThat(result).hasSize(1)
        assertThat(result[0].usage.inputTokens).isEqualTo(0)
        assertThat(result[0].usage.outputTokens).isEqualTo(0)
    }
}
