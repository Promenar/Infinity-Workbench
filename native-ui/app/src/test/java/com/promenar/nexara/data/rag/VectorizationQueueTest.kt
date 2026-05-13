package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.DocumentDao
import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.dao.VectorizationTaskDao
import com.promenar.nexara.data.local.db.entity.DocumentEntity
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VectorizationQueueTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val vectorStore: VectorStore = mockk(relaxed = true)
    private val embeddingClient: EmbeddingClient = mockk(relaxed = true)
    private val graphExtractor: GraphExtractor? = null
    private val documentDao: DocumentDao = mockk(relaxed = true)
    private val vectorDao: VectorDao = mockk(relaxed = true)
    private val vectorizationTaskDao: VectorizationTaskDao = mockk(relaxed = true)

    private val testDoc = DocumentEntity(
        id = "doc-1",
        title = "Test Document",
        content = "This is a test document content for vectorization testing.",
        type = "text/plain",
        vectorized = 0,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        MockKAnnotations.init(this, relaxUnitFun = true)

        coEvery { documentDao.getById("doc-1") } returns testDoc
        coEvery { documentDao.updateVectorized(any(), any()) } returns Unit
        coEvery { documentDao.updateVectorizationStatusWithHash(any(), any(), any(), any()) } returns Unit
        coEvery { vectorizationTaskDao.insert(any()) } returns Unit
        coEvery { vectorizationTaskDao.delete(any()) } returns Unit
        coEvery { vectorizationTaskDao.deleteCompletedTasks() } returns Unit
        coEvery { vectorizationTaskDao.getRecoverableTasks() } returns emptyList()
        coEvery { vectorizationTaskDao.getById(any()) } returns null
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun createQueue(config: RagConfiguration = RagConfiguration()): VectorizationQueue {
        return VectorizationQueue(
            vectorStore = vectorStore,
            embeddingClient = embeddingClient,
            graphExtractor = graphExtractor,
            documentDao = documentDao,
            vectorDao = vectorDao,
            vectorizationTaskDao = vectorizationTaskDao,
            ragConfig = config,
            dispatcher = testDispatcher
        )
    }

    // ── 入队与初始状态 ────────────────────────────────────────────

    @Test
    fun `enqueueDocument adds task to queue`() = runTest {
        val queue = createQueue()
        val states = mutableListOf<VectorizationQueue.QueueState>()
        queue.setOnStateChange { q, _ -> states.add(queue.getState()) }

        // 使用成功 mock 以避免处理链继续
        mockSuccessfulEmbedding()

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        advanceUntilIdle()

        assertThat(states).isNotEmpty()
        val firstState = states.first()
        assertThat(firstState.queue).hasSize(1)
        assertThat(firstState.queue[0].docId).isEqualTo("doc-1")
        assertThat(firstState.queue[0].docTitle).isEqualTo("Test Doc")
        assertThat(firstState.queue[0].type).isEqualTo("document")
        assertThat(firstState.queue[0].status).isAnyOf("pending", "chunking", "vectorizing", "saving", "completed")
    }

    @Test
    fun `enqueueDocument saves task to database`() = runTest {
        val queue = createQueue()
        mockSuccessfulEmbedding()

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        advanceUntilIdle()

        coVerify(atLeast = 1) { vectorizationTaskDao.insert(any()) }
    }

    @Test
    fun `enqueueDocument sets document vectorized to 1`() = runTest {
        val queue = createQueue()
        mockSuccessfulEmbedding()

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        advanceUntilIdle()

        coVerify { documentDao.updateVectorized("doc-1", 1) }
    }

    @Test
    fun `enqueueDocument with skipVectorization does not set vectorized`() = runTest {
        val queue = createQueue()
        mockSuccessfulEmbedding()

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "", skipVectorization = true)

        advanceUntilIdle()

        coVerify(exactly = 0) { documentDao.updateVectorized("doc-1", 1) }
    }

    @Test
    fun `getQueueLength reflects actual queue size`() = runTest {
        val queue = createQueue()
        mockSuccessfulEmbedding()

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        advanceUntilIdle()

        // queue should be empty after processing
        assertThat(queue.getQueueLength()).isEqualTo(0)
    }

    // ── 进度状态转换 ──────────────────────────────────────────────

    @Test
    fun `processDocumentTask transitions through correct progress states`() = runTest {
        val queue = createQueue()
        val progressValues = mutableListOf<Pair<String, Double>>()

        mockSuccessfulEmbedding()

        queue.setOnStateChange { _, currentTask ->
            currentTask?.let { progressValues.add(it.status to it.progress) }
        }

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        advanceUntilIdle()

        // 预期进度流程: chunking(10.0) → vectorizing(20.0) → ... → saving(85.0) → completed(100.0)
        val statuses = progressValues.map { it.first }
        assertThat(statuses).contains("chunking")
        assertThat(statuses).contains("vectorizing")
        assertThat(statuses).contains("saving")
        assertThat(statuses.last()).isEqualTo("completed")
    }

    @Test
    fun `vectorizing progress starts at 20 percent`() = runTest {
        val queue = createQueue()
        val progressSnapshots = mutableListOf<Double>()

        mockSuccessfulEmbedding()

        queue.setOnStateChange { _, currentTask ->
            if (currentTask?.status == "vectorizing") {
                progressSnapshots.add(currentTask.progress)
            }
        }

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        advanceUntilIdle()

        assertThat(progressSnapshots).isNotEmpty()
        assertThat(progressSnapshots.first()).isEqualTo(20.0)
    }

    @Test
    fun `completed task reaches 100 percent progress`() = runTest {
        val queue = createQueue()
        var finalProgress = 0.0

        mockSuccessfulEmbedding()

        queue.setOnStateChange { _, currentTask ->
            if (currentTask?.status == "completed") {
                finalProgress = currentTask.progress
            }
        }

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        advanceUntilIdle()

        assertThat(finalProgress).isEqualTo(100.0)
    }

    // ── 成功完成 ──────────────────────────────────────────────────

    @Test
    fun `successful vectorization updates document vectorization status`() = runTest {
        val queue = createQueue()
        mockSuccessfulEmbedding()

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        advanceUntilIdle()

        coVerify { documentDao.updateVectorizationStatusWithHash("doc-1", 2, any(), any()) }
    }

    @Test
    fun `successful vectorization removes task from database`() = runTest {
        val queue = createQueue()
        mockSuccessfulEmbedding()

        val taskEntitySlot = slot<VectorizationTaskEntity>()
        coEvery { vectorizationTaskDao.getById(any()) } answers {
            if (taskEntitySlot.isCaptured) taskEntitySlot.captured else null
        }
        coEvery { vectorizationTaskDao.insert(capture(taskEntitySlot)) } returns Unit

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        advanceUntilIdle()

        coVerify(atLeast = 1) { vectorizationTaskDao.delete(any()) }
    }

    // ── 失败与重试 ────────────────────────────────────────────────

    @Test
    fun `non-retryable error fails immediately`() = runTest {
        val queue = createQueue()
        coEvery { embeddingClient.embedDocuments(any()) } throws IllegalStateException("API key invalid")

        var failedStatus: String? = null
        var failedError: String? = null
        queue.setOnStateChange { _, currentTask ->
            if (currentTask?.status == "failed") {
                failedStatus = currentTask.status
                failedError = currentTask.error
            }
        }

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        advanceUntilIdle()

        assertThat(failedStatus).isEqualTo("failed")
        assertThat(failedError).isNotNull()
    }

    @Test
    fun `retryable error retries up to MAX_RETRIES`() = runTest {
        val queue = createQueue()
        // "network timeout" contains "network" → isRetryable
        coEvery { embeddingClient.embedDocuments(any()) } throws IllegalStateException("network timeout")

        var retryCount = 0
        var finalStatus: String? = null
        queue.setOnStateChange { _, currentTask ->
            currentTask?.subStatus?.let {
                if (it.startsWith("Retrying")) retryCount++
            }
            if (currentTask?.status == "failed") finalStatus = "failed"
        }

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        advanceUntilIdle()

        // MAX_RETRIES = 3
        assertThat(retryCount).isEqualTo(3)
        assertThat(finalStatus).isEqualTo("failed")
    }

    @Test
    fun `failed task sets document vectorized to -1`() = runTest {
        val queue = createQueue()
        coEvery { embeddingClient.embedDocuments(any()) } throws IllegalStateException("API key invalid")

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        advanceUntilIdle()

        coVerify { documentDao.updateVectorized("doc-1", -1) }
    }

    @Test
    fun `failed task does not reset vectorized when skipVectorization is true`() = runTest {
        val queue = createQueue()
        coEvery { embeddingClient.embedDocuments(any()) } throws IllegalStateException("API key invalid")

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "", skipVectorization = true)

        advanceUntilIdle()

        coVerify(exactly = 0) { documentDao.updateVectorized("doc-1", -1) }
    }

    @Test
    fun `http 500 error is treated as retryable`() = runTest {
        val queue = createQueue()
        // "5" matches the retry predicate
        coEvery { embeddingClient.embedDocuments(any()) } throws IllegalStateException("HTTP 500 Internal Server Error")

        var retrySeen = false
        queue.setOnStateChange { _, currentTask ->
            if (currentTask?.subStatus?.startsWith("Retrying") == true) retrySeen = true
        }

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        advanceUntilIdle()

        assertThat(retrySeen).isTrue()
    }

    @Test
    fun `fetch failed error is treated as retryable`() = runTest {
        val queue = createQueue()
        coEvery { embeddingClient.embedDocuments(any()) } throws IllegalStateException("fetch failed")

        var retrySeen = false
        queue.setOnStateChange { _, currentTask ->
            if (currentTask?.subStatus?.startsWith("Retrying") == true) retrySeen = true
        }

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        advanceUntilIdle()

        assertThat(retrySeen).isTrue()
    }

    // ── 跳过向量化 ────────────────────────────────────────────────

    @Test
    fun `skipVectorization flag bypasses embedding calls`() = runTest {
        val queue = createQueue()

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "", skipVectorization = true)

        advanceUntilIdle()

        coVerify(exactly = 0) { embeddingClient.embedDocuments(any()) }
    }

    @Test
    fun `skipVectorization does not add vector records`() = runTest {
        val queue = createQueue()

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "", skipVectorization = true)

        advanceUntilIdle()

        coVerify(exactly = 0) { vectorStore.addVectorRecords(any()) }
    }

    // ── 增量哈希 ──────────────────────────────────────────────────

    @Test
    fun `incremental hash skips re-vectorization when hash matches`() = runTest {
        val config = RagConfiguration(enableIncrementalHash = true)
        val queue = createQueue(config)

        val alreadyVectorized = testDoc.copy(
            vectorized = 2,
            contentHash = VectorizationQueueTestHelper.simpleHash(testDoc.content ?: "")
        )
        coEvery { documentDao.getById("doc-1") } returns alreadyVectorized

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        advanceUntilIdle()

        coVerify(exactly = 0) { embeddingClient.embedDocuments(any()) }
    }

    // ── 取消 ──────────────────────────────────────────────────────

    @Test
    fun `cancel removes task from queue by docId`() = runTest {
        val queue = createQueue()
        mockSuccessfulEmbedding()

        // 入队但不处理
        coEvery { embeddingClient.embedDocuments(any()) } coAnswers {
            // block to keep task in queue
            kotlinx.coroutines.delay(10000)
            EmbeddingResult(embeddings = emptyList(), usage = null)
        }

        queue.enqueueDocument("doc-1", "Test Doc", testDoc.content ?: "")

        // 给一点时间让任务开始
        kotlinx.coroutines.delay(50)

        queue.cancel("doc-1")

        assertThat(queue.getQueueLength()).isEqualTo(0)
    }

    // ── 中断恢复 ──────────────────────────────────────────────────

    @Test
    fun `resumeInterruptedTasks restores persisted tasks`() = runTest {
        val queue = createQueue()
        mockSuccessfulEmbedding()

        val interruptedEntity = VectorizationTaskEntity(
            id = "task-interrupted",
            type = "document",
            status = "vectorizing",
            docId = "doc-1",
            docTitle = "Interrupted Doc",
            lastChunkIndex = 5,
            totalChunks = 20,
            progress = 35.0,
            createdAt = System.currentTimeMillis() - 60_000,
            updatedAt = System.currentTimeMillis() - 60_000
        )
        coEvery { vectorizationTaskDao.getRecoverableTasks() } returns listOf(interruptedEntity)

        // 重置 document state
        coEvery { documentDao.getById("doc-1") } returns testDoc

        queue.resumeInterruptedTasks()

        advanceUntilIdle()

        assertThat(queue.getQueueLength()).isEqualTo(0) // processed after resume
    }

    @Test
    fun `resumeInterruptedTasks does nothing when no tasks`() = runTest {
        val queue = createQueue()
        coEvery { vectorizationTaskDao.getRecoverableTasks() } returns emptyList()

        queue.resumeInterruptedTasks()

        // should complete without error
        assertThat(queue.getQueueLength()).isEqualTo(0)
    }

    // ── 本地预处理 ────────────────────────────────────────────────

    @Test
    fun `localPreprocess strips HTML tags from content`() = runTest {
        val config = RagConfiguration(enableLocalPreprocess = true)
        val queue = createQueue(config)

        val htmlDoc = testDoc.copy(
            content = "<p>Hello <b>World</b></p>\n<span>Test</span>"
        )
        coEvery { documentDao.getById("doc-1") } returns htmlDoc
        mockSuccessfulEmbedding()

        queue.enqueueDocument("doc-1", "HTML Doc", htmlDoc.content ?: "")

        advanceUntilIdle()
        // 验证 embeddingClient 收到的文本已被预处理（不含 HTML 标签）
        coVerify { embeddingClient.embedDocuments(match { texts ->
            texts.none { it.contains("<") || it.contains(">") }
        }) }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────

    private fun mockSuccessfulEmbedding() {
        coEvery { embeddingClient.embedDocuments(any()) } answers {
            val texts = firstArg<List<String>>()
            val embeddings = texts.map { FloatArray(3) { i -> (i + 1).toFloat() } }
            EmbeddingResult(embeddings = embeddings, usage = null)
        }
    }
}

/** 暴露 simpleHash 用于测试 */
internal object VectorizationQueueTestHelper {
    fun simpleHash(str: String): String {
        var hash = 0
        for (c in str) {
            hash = (hash shl 5) - hash + c.code
            hash = hash and hash
        }
        return hash.toString(36) + str.length.toString(36)
    }
}
