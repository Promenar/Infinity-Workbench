package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.RagOptions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MemoryManagerRagAdapterTest {
    @Test
    fun `Adapter完整传递文档与文件夹范围而不丢失约束`() = runTest {
        val manager = mockk<MemoryManager>()
        val captured = slot<MemoryManager.RetrieveOptions>()
        every { manager.ragConfig } returns RagConfiguration()
        coEvery {
            manager.retrieveContext(
                query = any(),
                sessionId = any(),
                options = capture(captured),
                onProgress = null,
            )
        } returns MemoryManager.RetrieveResult(
            context = "",
            references = emptyList(),
            metadata = MemoryManager.RetrieveMetadata(),
        )
        val adapter = MemoryManagerRagAdapter(manager)

        adapter.retrieveContext(
            query = "scope",
            sessionId = "session-a",
            options = RagOptions(
                enableMemory = false,
                enableDocs = true,
                activeDocIds = listOf("doc-a"),
                activeFolderIds = listOf("folder-a"),
                isGlobal = true,
                documentScope = com.promenar.nexara.data.model.DocumentScope.WORKSPACE,
            ),
            onProgress = null,
        )

        assertThat(captured.captured.activeDocIds).containsExactly("doc-a")
        assertThat(captured.captured.activeFolderIds).containsExactly("folder-a")
        // 旧字段 isGlobal 只在未设置新字段时参与解析。
        assertThat(captured.captured.memoryScope).isEqualTo(com.promenar.nexara.data.model.ConversationScope.AGENT)
        assertThat(captured.captured.documentScope).isEqualTo(com.promenar.nexara.data.model.DocumentScope.WORKSPACE)
    }
    @Test
    fun `会话调优值不能覆盖全局参数且开关取会话`() = runTest {
        val manager = mockk<MemoryManager>()
        val global = RagConfiguration(memoryLimit = 8, docLimit = 15, rerankTopK = 60,
            rerankFinalK = 10, enableMemory = false, enableDocs = false, enableRerank = false)
        val captured = slot<MemoryManager.RetrieveOptions>()
        every { manager.ragConfig } returns global
        coEvery { manager.retrieveContext(any(), any(), capture(captured), any()) } returns
            MemoryManager.RetrieveResult("", emptyList(), MemoryManager.RetrieveMetadata())
        MemoryManagerRagAdapter(manager).retrieveContext("q", "s", RagOptions(
            memoryLimit = 1, memoryThreshold = 0.1f, docLimit = 1, docThreshold = 0.1f,
            rerankTopK = 1, rerankFinalK = 1, enableQueryRewrite = false,
            queryRewriteStrategy = "hyde", queryRewriteCount = 1, enableHybridSearch = false,
            hybridAlpha = 0.1f, hybridBM25Boost = 9f,
        ), null)
        val override = captured.captured.configOverride!!
        assertThat(override.copy(enableMemory = false, enableDocs = false, enableRerank = false)).isEqualTo(global)
        // 全局开关只作新会话初值，检索时以会话开关为准。
        assertThat(captured.captured.enableMemory).isTrue()
        assertThat(captured.captured.enableDocs).isTrue()
        assertThat(captured.captured.enableRerank).isTrue()
        assertThat(override.enableMemory).isTrue()
        assertThat(override.enableRerank).isTrue()
    }
}
