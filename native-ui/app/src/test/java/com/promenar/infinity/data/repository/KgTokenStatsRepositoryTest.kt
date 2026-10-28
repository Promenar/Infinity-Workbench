package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.MessageDao
import com.promenar.infinity.data.local.db.dao.ModelTokenRow
import com.promenar.infinity.data.local.db.dao.TokenTotalRow
import com.promenar.infinity.data.rag.KgTokenUsage
import com.promenar.infinity.data.rag.KgUsageRecord
import com.promenar.infinity.data.rag.TestKgUsageStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class KgTokenStatsRepositoryTest {
    @Test
    fun `KG用量同时进入总计模型和独立分类且不重复累计`() = runTest {
        val messages = mockk<MessageDao>()
        coEvery { messages.getTotalTokenUsage() } returns TokenTotalRow(100, 20)
        coEvery { messages.getTokenUsageByModel() } returns listOf(ModelTokenRow("owner::model", 100, 20))
        val kg = TestKgUsageStore()
        kg.record(KgUsageRecord("owner::model", KgTokenUsage(17, 7)))
        val repository = TokenStatsRepository(messages, kg)

        val total = repository.getTotalUsage()
        val model = repository.getUsageByModel().single()
        val categories = repository.getUsageByCategory()

        assertThat(total.inputTokens).isEqualTo(117)
        assertThat(total.outputTokens).isEqualTo(27)
        assertThat(model.usage).isEqualTo(total)
        assertThat(categories.single { it.category == "kg" }.usage.inputTokens).isEqualTo(17)
        assertThat(categories.single { it.category == "kg" }.calls).isEqualTo(1)
        assertThat(categories.sumOf { it.usage.totalTokens }).isEqualTo(144)
    }
}
