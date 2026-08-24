package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.entity.VectorEntity
import io.mockk.coEvery
import io.mockk.coVerify
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

    @Test
    fun `workspace search delegates both FTS and fallback to the same root scope`() = runTest {
        val options = KeywordSearcher.SearchOptions(workspaceRootUuid = "root-a")
        coEvery {
            vectorDao.searchFtsByWorkspaceRoot("needle", "root-a")
        } throws IllegalStateException("fts unavailable")
        coEvery { vectorDao.getByWorkspaceRoot("root-a") } returns listOf(
            VectorEntity(
                id = "root-a-chunk",
                docId = "doc-a",
                content = "needle body",
                embedding = byteArrayOf(),
                createdAt = 1,
            ),
        )

        val outcome = KeywordSearcher(vectorDao).searchWithOutcome(
            "needle",
            limit = 20,
            options = options,
        )

        assertThat(outcome.mode).isEqualTo(KeywordSearchMode.LikeFallback)
        assertThat(outcome.results.map { it.docId }).containsExactly("doc-a")
        coVerify(exactly = 0) { vectorDao.searchFts("needle") }
        coVerify(exactly = 0) { vectorDao.getAll() }
    }

    @Test
    fun `workspace FTS candidates are ordered by BM25 before applying limit`() = runTest {
        coEvery { vectorDao.searchFtsByWorkspaceRoot("needle", "root-a") } returns listOf(
            VectorEntity("low", "doc-low", content = "needle once", embedding = byteArrayOf(), createdAt = 1),
            VectorEntity("high", "doc-high", content = "needle needle needle needle", embedding = byteArrayOf(), createdAt = 1),
        )

        val results = KeywordSearcher(vectorDao).search(
            "needle",
            limit = 1,
            options = KeywordSearcher.SearchOptions(workspaceRootUuid = "root-a"),
        )

        assertThat(results.map { it.docId }).containsExactly("doc-high")
    }

    @Test
    fun `workspace BM25 ties use stable document and chunk identity order`() = runTest {
        coEvery { vectorDao.searchFtsByWorkspaceRoot("needle", "root-a") } returns listOf(
            VectorEntity("z-chunk", "doc-z", content = "needle", embedding = byteArrayOf(), createdAt = 1),
            VectorEntity("b-chunk", "doc-a", content = "needle", embedding = byteArrayOf(), createdAt = 1),
            VectorEntity("a-chunk", "doc-a", content = "needle", embedding = byteArrayOf(), createdAt = 1),
        )

        val results = KeywordSearcher(vectorDao).search(
            "needle",
            limit = 3,
            options = KeywordSearcher.SearchOptions(workspaceRootUuid = "root-a"),
        )

        assertThat(results.map { it.id })
            .containsExactly("a-chunk", "b-chunk", "z-chunk")
            .inOrder()
    }
}
