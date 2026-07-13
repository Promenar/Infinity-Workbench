package com.promenar.nexara.data.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.SessionEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryManagerDocumentScopeTest {
    private lateinit var database: NexaraDatabase
    private lateinit var vectorStore: VectorStore
    private lateinit var embedding: EmbeddingClient
    private lateinit var manager: MemoryManager

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seedSession("session-a")
        seedSession("session-b")

        vectorStore = VectorStore(
            database.vectorDao(),
            database.kgNodeDao(),
            database.kgEdgeDao(),
        )
        vectorStore.addVectorRecords(
            listOf(
                VectorStore.NewVectorRecord(
                    docId = "doc-a",
                    content = "document alpha",
                    embedding = floatArrayOf(1f, 0f),
                    metadata = documentVectorMetadata("doc-a", 0, "alpha.txt"),
                ),
                VectorStore.NewVectorRecord(
                    docId = "doc-b",
                    content = "document beta",
                    embedding = floatArrayOf(1f, 0f),
                    metadata = documentVectorMetadata("doc-b", 0, "beta.txt"),
                ),
                VectorStore.NewVectorRecord(
                    sessionId = "session-a",
                    content = "memory alpha",
                    embedding = floatArrayOf(1f, 0f),
                    metadata = "{\"type\":\"memory\"}",
                ),
                VectorStore.NewVectorRecord(
                    sessionId = "session-b",
                    content = "memory beta",
                    embedding = floatArrayOf(1f, 0f),
                    metadata = "{\"type\":\"memory\"}",
                ),
            ),
        )
        embedding = mockk<EmbeddingClient>()
        every { embedding.isConfigured } returns true
        every { embedding.hasLocalFallback } returns false
        coEvery { embedding.embedQuery(any()) } returns (floatArrayOf(1f, 0f) to null)
        manager = createManager(enableHybridSearch = false)
    }

    private fun createManager(enableHybridSearch: Boolean) = MemoryManager(
            vectorStore = vectorStore,
            keywordSearcher = KeywordSearcher(database.vectorDao()),
            graphStore = GraphStore(database.kgNodeDao(), database.kgEdgeDao()),
            embeddingClient = embedding,
            ragConfig = RagConfiguration(
                enableQueryRewrite = false,
                enableHybridSearch = enableHybridSearch,
                enableRerank = false,
                memoryThreshold = 0.1f,
                docThreshold = 0.1f,
                memoryLimit = 10,
                docLimit = 10,
                rerankTopK = 10,
            ),
        )

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `关闭文档检索时绝不返回文档`() = runTest {
        val result = retrieve(
            MemoryManager.RetrieveOptions(
                enableMemory = true,
                enableDocs = false,
                activeDocIds = listOf("doc-a"),
                isGlobal = true,
                sessionId = "session-a",
            ),
        )

        assertThat(result.references.mapNotNull { it.documentId }).isEmpty()
        assertThat(result.references.map { it.content })
            .containsExactly("memory alpha", "memory beta")
    }

    @Test
    fun `非全局文档检索在活动文档为空时返回零文档`() = runTest {
        val result = retrieve(
            MemoryManager.RetrieveOptions(
                enableMemory = false,
                enableDocs = true,
                activeDocIds = emptyList(),
                isGlobal = false,
                sessionId = "session-a",
            ),
        )

        assertThat(result.references).isEmpty()
    }

    @Test
    fun `非全局仅有未展开文件夹范围时不得退化为全库检索`() = runTest {
        val result = retrieve(
            MemoryManager.RetrieveOptions(
                enableMemory = false,
                enableDocs = true,
                activeDocIds = emptyList(),
                activeFolderIds = listOf("folder-a"),
                isGlobal = false,
                sessionId = "session-a",
            ),
        )

        assertThat(result.references).isEmpty()
    }

    @Test
    fun `混合检索在非全局空文档范围时也不得通过关键词路径泄露文档`() = runTest {
        val result = createManager(enableHybridSearch = true).retrieveContext(
            query = "memory",
            sessionId = "session-a",
            options = MemoryManager.RetrieveOptions(
                enableMemory = true,
                enableDocs = true,
                activeDocIds = emptyList(),
                isGlobal = false,
                sessionId = "session-a",
            ),
        )

        assertThat(result.references.map { it.content }).containsExactly("memory alpha")
        assertThat(result.references.mapNotNull { it.documentId }).isEmpty()
    }

    @Test
    fun `非全局文档检索只能命中显式活动文档`() = runTest {
        val result = retrieve(
            MemoryManager.RetrieveOptions(
                enableMemory = false,
                enableDocs = true,
                activeDocIds = listOf("doc-a"),
                isGlobal = false,
                sessionId = "session-a",
            ),
        )

        assertThat(result.references.mapNotNull { it.documentId }).containsExactly("doc-a")
        assertThat(result.references.single().source).isEqualTo("文档: alpha.txt")
    }

    @Test
    fun `全局文档检索才允许命中全部文档`() = runTest {
        val result = retrieve(
            MemoryManager.RetrieveOptions(
                enableMemory = false,
                enableDocs = true,
                activeDocIds = emptyList(),
                isGlobal = true,
                sessionId = "session-a",
            ),
        )

        assertThat(result.references.mapNotNull { it.documentId })
            .containsExactly("doc-a", "doc-b")
    }

    @Test
    fun `非全局记忆检索与文档范围互不串扰且不跨会话`() = runTest {
        val result = retrieve(
            MemoryManager.RetrieveOptions(
                enableMemory = true,
                enableDocs = true,
                activeDocIds = listOf("doc-b"),
                isGlobal = false,
                sessionId = "session-a",
            ),
        )

        assertThat(result.references.map { it.content })
            .containsExactly("memory alpha", "document beta")
        assertThat(result.references.map { it.content }).doesNotContain("memory beta")
        assertThat(result.references.mapNotNull { it.documentId }).containsExactly("doc-b")
    }

    private suspend fun retrieve(options: MemoryManager.RetrieveOptions) =
        manager.retrieveContext("scope query", "session-a", options)

    private suspend fun seedSession(id: String) {
        val now = System.currentTimeMillis()
        database.sessionDao().insert(
            SessionEntity(
                id = id,
                agentId = "test-agent",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
