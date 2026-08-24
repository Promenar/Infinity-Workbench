package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.entity.VectorEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class KeywordSearcherTest {
    private val vectorDao = mockk<VectorDao>()

    @Test
    fun `FTS失败但LIKE回退成功时outcome仍明确标记degraded`() = runTest {
        coEvery { vectorDao.searchFts("needle") } throws IllegalStateException("fts unavailable")
        coEvery { vectorDao.getAll() } returns listOf(
            VectorEntity(
                id = "chunk-1",
                docId = "doc-1",
                content = "needle body",
                embedding = byteArrayOf(),
                createdAt = 1,
            ),
        )

        val outcome = KeywordSearcher(vectorDao).searchWithOutcome("needle", limit = 20)

        assertThat(outcome.mode).isEqualTo(KeywordSearchMode.LikeFallback)
        assertThat(outcome.results.map { it.docId }).containsExactly("doc-1")
        assertThat(outcome.technical).isEqualTo("IllegalStateException")
    }

    @Test
    fun `FTS成功时outcome标记为FTS且保留排序结果`() = runTest {
        coEvery { vectorDao.searchFts("needle") } returns listOf(
            VectorEntity(
                id = "chunk-1",
                docId = "doc-1",
                content = "needle body",
                embedding = byteArrayOf(),
                createdAt = 1,
            ),
        )

        val outcome = KeywordSearcher(vectorDao).searchWithOutcome("needle", limit = 20)

        assertThat(outcome.mode).isEqualTo(KeywordSearchMode.Fts)
        assertThat(outcome.results.map { it.docId }).containsExactly("doc-1")
        assertThat(outcome.technical).isNull()
    }
}
