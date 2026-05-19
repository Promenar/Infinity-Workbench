package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class GraphExtractorTest {

    private lateinit var tempDir: File
    private lateinit var mockApp: NexaraApplication
    private val protocol: LlmProtocol = mockk()
    private val graphStore: GraphStore = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("nexara_test_cache").toFile()
        mockApp = mockk(relaxed = true)
        every { mockApp.cacheDir } returns tempDir

        // 反射设置 NexaraApplication.instance
        val field = NexaraApplication::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, mockApp)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
        
        // 重置 instance
        val field = NexaraApplication::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    @Test
    fun `extractAndSave split text and extracts correctly without docId`() = runTest {
        val extractor = GraphExtractor(
            protocol = protocol,
            graphStore = graphStore,
            chunkSize = 10,
            chunkOverlap = 0
        )

        // text 长度 25，按 chunkSize=10 会被切分成 3 个 chunks
        // "1234567890", "abcdefghij", "klmno"
        val text = "1234567890abcdefghijklmno"

        coEvery { protocol.sendPromptSync(any()) } returnsMany listOf(
            PromptResponse(
                content = """{
                    "nodes": [{"name": "NodeA", "type": "concept"}],
                    "edges": [{"source": "NodeA", "target": "NodeB", "relation": "test", "weight": 0.8}]
                }"""
            ),
            PromptResponse(
                content = """{
                    "nodes": [{"name": "NodeB", "type": "concept"}],
                    "edges": []
                }"""
            ),
            PromptResponse(
                content = """{"nodes": [], "edges": []}"""
            )
        )

        val result = extractor.extractAndSave(text)

        assertThat(result.error).isNull()
        assertThat(result.nodes).hasSize(2)
        assertThat(result.nodes.map { it.name }).containsExactly("NodeA", "NodeB")
        
        // 验证没有 docId 时没有创建任何 checkpoint 文件夹
        val checkpointRoot = File(tempDir, "kg_extraction_checkpoint")
        assertThat(checkpointRoot.exists()).isFalse()
    }

    @Test
    fun `extractAndSave saves progressive checkpoints and resumes after interruption`() = runTest {
        val docId = "test-doc-123"
        val extractor = GraphExtractor(
            protocol = protocol,
            graphStore = graphStore,
            chunkSize = 10,
            chunkOverlap = 0
        )

        // "1234567890", "abcdefghij" (2 chunks)
        val text = "1234567890abcdefghij"

        // 第一次尝试：第 1 个 chunk 成功，第 2 个 chunk 超时/返回 Error
        coEvery { protocol.sendPromptSync(any()) } coAnswers {
            val req = firstArg<PromptRequest>()
            // 我们通过判断提示词里包含的内容来区分 chunk 0 和 chunk 1
            val isChunk0 = req.messages.any { it.content.contains("1234567890") }
            if (isChunk0) {
                PromptResponse(
                    content = """{
                        "nodes": [{"name": "NodeA", "type": "concept"}],
                        "edges": [{"source": "NodeA", "target": "NodeB", "relation": "link", "weight": 0.9}]
                    }"""
                )
            } else {
                throw RuntimeException("Timeout exception")
            }
        }

        val result1 = extractor.extractAndSave(text, docId = docId)

        // 验证第一次返回的是中断错误，且没有 upsert 进数据库（原子写保护）
        assertThat(result1.error).contains("Extraction interrupted")
        coVerify(exactly = 0) { graphStore.clearGraphForDoc(any()) }
        coVerify(exactly = 0) { graphStore.upsertNode(any(), any(), any(), any()) }

        // 验证 checkpoint 文件已成功落盘
        val chunk0File = File(tempDir, "kg_extraction_checkpoint/$docId/chunk_0.json")
        val chunk1File = File(tempDir, "kg_extraction_checkpoint/$docId/chunk_1.json")
        assertThat(chunk0File.exists()).isTrue()
        assertThat(chunk1File.exists()).isFalse()

        // 第二次尝试：续传！
        // 清理 mock 的协议调用计数
        clearMocks(protocol)

        // 此时由于 chunk_0.json 已经存在，应该只向 LLM 发送 chunk 1 的请求
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse(
            content = """{
                "nodes": [{"name": "NodeB", "type": "concept"}],
                "edges": []
            }"""
        )

        val result2 = extractor.extractAndSave(text, docId = docId)

        // 验证完全成功，最终节点合并，旧图谱原子清除落库，并打扫干净 checkpoint
        assertThat(result2.error).isNull()
        assertThat(result2.nodes.map { it.name }).containsExactly("NodeA", "NodeB")

        // 验证只请求了 1 次（只请求了未完成的 chunk 1，chunk 0 则是秒级从 checkpoint 缓存中加载）
        coVerify(exactly = 1) { protocol.sendPromptSync(any()) }

        // 验证事务清理旧图谱且落库成功
        coVerify(exactly = 1) { graphStore.clearGraphForDoc(docId) }
        coVerify(atLeast = 1) { graphStore.upsertNode(any(), any(), any(), any()) }

        // 验证完全提取落库后，打扫临时 checkpoint 缓存，防止垃圾垃圾残留
        val checkpointDir = File(tempDir, "kg_extraction_checkpoint/$docId")
        assertThat(checkpointDir.exists()).isFalse()
    }
}
