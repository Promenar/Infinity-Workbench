package com.promenar.nexara.data.rag

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.model.DocumentScope
import com.promenar.nexara.data.model.RagOptions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MemoryManagerQueryRewriteTest {
    private lateinit var database: NexaraDatabase
    private lateinit var adapter: MemoryManagerRagAdapter
    private lateinit var embedding: EmbeddingClient
    private lateinit var rewriter: QueryRewriter

    @Before
    fun setup() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NexaraDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        database.sessionDao().insert(SessionEntity("session", "agent", createdAt = 1, updatedAt = 1))
        val store = VectorStore(database.vectorDao(), database.kgNodeDao(), database.kgEdgeDao(), database.kgEvidenceDao())
        store.addVectorRecords(listOf(VectorStore.NewVectorRecord(docId = "doc", content = "资料片段",
            embedding = floatArrayOf(1f, 0f), metadata = documentVectorMetadata("doc", 0, "资料.md"))))
        embedding = mockk()
        every { embedding.isConfigured } returns true
        every { embedding.hasLocalFallback } returns false
        coEvery { embedding.embedQuery(any()) } returns (floatArrayOf(1f, 0f) to null)
        rewriter = mockk()
        coEvery { rewriter.rewrite(any(), any(), any(), any()) } returns RewriteResult(listOf("原始查询", "改写查询"))
        adapter = MemoryManagerRagAdapter(MemoryManager(store, KeywordSearcher(database.vectorDao()), GraphStore(database),
            embedding, queryRewriter = rewriter, ragConfig = RagConfiguration(enableQueryRewrite = true,
                enableHybridSearch = false, enableRerank = false, docThreshold = 0.1f)))
    }

    @After
    fun close() = database.close()

    private fun options() = RagOptions(enableMemory = false, enableDocs = true, enableRerank = false,
        documentScope = DocumentScope.ALL_INDEXED)

    @Test
    fun `工具检索直接嵌入原查询且不调用查询改写`() = runBlocking<Unit> {
        val result = adapter.retrieveForTool("原始查询", "session", options(), null)

        assertThat(result.references.map { it.documentId }).containsExactly("doc")
        assertThat(result.context).contains("[K1 · 文档《资料.md》]: 资料片段")
        coVerify(exactly = 0) { rewriter.rewrite(any(), any(), any(), any()) }
        coVerify(exactly = 1) { embedding.embedQuery("原始查询") }
        coVerify(exactly = 0) { embedding.embedQuery("改写查询") }
    }

    @Test
    fun `自动检索保持查询改写与多查询嵌入`() = runBlocking<Unit> {
        val result = adapter.retrieveContext("原始查询", "session", options(), null)

        assertThat(result.references.map { it.documentId }).containsExactly("doc")
        assertThat(result.context).contains("[K1 · 文档《资料.md》]: 资料片段")
        coVerify(exactly = 1) { rewriter.rewrite("原始查询", any(), any(), any()) }
        coVerify(exactly = 1) { embedding.embedQuery("原始查询") }
        coVerify(exactly = 1) { embedding.embedQuery("改写查询") }
    }
}
