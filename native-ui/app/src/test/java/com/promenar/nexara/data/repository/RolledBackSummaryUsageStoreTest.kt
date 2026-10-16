package com.promenar.nexara.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.DailyTokenRow
import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.data.local.db.dao.ModelTokenRow
import com.promenar.nexara.data.local.db.dao.SessionTokenRow
import com.promenar.nexara.data.local.db.dao.TokenTotalRow
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.model.TokenUsage
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RolledBackSummaryUsageStoreTest {
    @Test
    fun `同一助手的累计摘要快照重复保存和更新后只统计一次`() = runTest {
        val preferences = preferences()
        val store = RolledBackSummaryUsageStore(preferences)
        val first = record(TokenUsage(37, 11, 48))
        store.preserveBeforeDiscard(listOf(first)) {}
        store.preserveBeforeDiscard(listOf(first)) {}
        store.preserveBeforeDiscard(listOf(first.copy(usage = TokenUsage(40, 12, 52, estimated = true)))) {}

        val restored = TokenStatsRepository(dao(), rolledBackSummaryUsageStore = RolledBackSummaryUsageStore(preferences))
        val usage = restored.getTotalUsage()

        assertThat(usage.inputTokens).isEqualTo(40L)
        assertThat(usage.outputTokens).isEqualTo(12L)
        assertThat(usage.estimated).isTrue()
    }

    @Test
    fun `回滚摘要进入全部统计维度且保留估算标记并在合并后选择会话排名`() = runTest {
        val preferences = preferences()
        val store = RolledBackSummaryUsageStore(preferences)
        val now = System.currentTimeMillis()
        val day = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().toString()
        store.preserveBeforeDiscard(listOf(record(TokenUsage(137, 11, 148, estimated = true), now))) {}
        val dao = dao()
        coEvery { dao.getTotalTokenUsage() } returns TokenTotalRow(101, 21)
        coEvery { dao.getTokenUsageByModel() } returns listOf(ModelTokenRow("model", 101, 21))
        coEvery { dao.getDailyTokenTrend(any()) } returns listOf(DailyTokenRow(day, 101, 21))
        coEvery { dao.getMessagesWithTokensBySession("session") } returns listOf(MessageEntity(
            "existing", "session", "assistant", "已有回复", tokens = """{"input":1,"output":1,"total":2}""", createdAt = now,
        ))
        coEvery { dao.getSessionTokenRanking(any()) } answers {
            listOf(SessionTokenRow("other", "已有高用量会话", 100, 20), SessionTokenRow("session", "目标会话", 1, 1))
                .take(firstArg())
        }
        val statistics = TokenStatsRepository(dao, rolledBackSummaryUsageStore = RolledBackSummaryUsageStore(preferences))

        val total = statistics.getTotalUsage()
        assertThat(total.inputTokens).isEqualTo(238L)
        assertThat(total.outputTokens).isEqualTo(32L)
        assertThat(total.estimated).isTrue()
        assertThat(statistics.getUsageByModel().single().usage).isEqualTo(total)
        val session = statistics.getUsageBySession("session")
        assertThat(session.inputTokens).isEqualTo(138L)
        assertThat(session.outputTokens).isEqualTo(12L)
        assertThat(session.estimated).isTrue()
        assertThat(statistics.getUsageBySession("unrelated").totalTokens).isEqualTo(0L)
        val ranked = statistics.getTopSessions(1).single()
        assertThat(ranked.sessionId).isEqualTo("session")
        assertThat(ranked.title).isEqualTo("目标会话")
        assertThat(ranked.totalTokens).isEqualTo(150L)
        val daily = statistics.getDailyTrend(7).single()
        assertThat(daily.day).isEqualTo(day)
        assertThat(daily.inputTokens).isEqualTo(238L)
        assertThat(daily.outputTokens).isEqualTo(32L)
        assertThat(statistics.getUsageByCategory().single { it.category == "chat" }.usage).isEqualTo(total)
    }

    @Test
    fun `过期摘要费用保留在总计但不会进入近期趋势`() = runTest {
        val preferences = preferences()
        RolledBackSummaryUsageStore(preferences).preserveBeforeDiscard(listOf(record(TokenUsage(37, 11, 48), 1L))) {}
        val statistics = TokenStatsRepository(dao(), rolledBackSummaryUsageStore = RolledBackSummaryUsageStore(preferences))

        assertThat(statistics.getTotalUsage().totalTokens).isEqualTo(48L)
        assertThat(statistics.getDailyTrend(7)).isEmpty()
    }

    private fun record(usage: TokenUsage, createdAt: Long = System.currentTimeMillis()) = RolledBackSummaryUsageRecord(
        messageId = "prepared-assistant", sessionId = "session", modelId = "model", usage = usage, createdAt = createdAt,
    )

    private fun preferences() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("rolled-back-summary-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    // Robolectric 的 SQLite 不含 JSON 扩展；SQL 聚合边界使用固定行，其余统计和账本读写使用生产实现。
    private fun dao(): MessageDao = mockk<MessageDao>().also { dao ->
        coEvery { dao.getById(any()) } returns null
        coEvery { dao.getTotalTokenUsage() } returns TokenTotalRow(0, 0)
        coEvery { dao.getTokenUsageByModel() } returns emptyList()
        coEvery { dao.getMessagesWithTokensBySession(any()) } returns emptyList()
        coEvery { dao.getSessionTokenRanking(any()) } returns emptyList()
        coEvery { dao.getDailyTokenTrend(any()) } returns emptyList()
    }
}
