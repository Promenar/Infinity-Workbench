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
                isGlobal = false,
            ),
            onProgress = null,
        )

        assertThat(captured.captured.activeDocIds).containsExactly("doc-a")
        assertThat(captured.captured.activeFolderIds).containsExactly("folder-a")
        assertThat(captured.captured.isGlobal).isFalse()
    }
}
