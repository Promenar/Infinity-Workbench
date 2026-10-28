package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RetrievalDepthTest {
    @Test fun `三档映射准确且只修改四个原始值`() {
        val base = RagConfiguration(enableRerank = false, enableDocs = false, memoryThreshold = 0.23f,
            docThreshold = 0.31f, kgExtractionModel = "kg", queryRewriteModel = "rewrite", docChunkSize = 1234)
        val mappings = listOf(listOf(3, 4, 20, 3), listOf(5, 8, 30, 5), listOf(8, 15, 60, 10))
        RetrievalDepth.entries.forEachIndexed { i, depth ->
            val result = base.withRetrievalDepth(depth)
            val values = mappings[i]
            assertThat(result).isEqualTo(base.copy(memoryLimit = values[0], docLimit = values[1], rerankTopK = values[2], rerankFinalK = values[3]))
            assertThat(result.retrievalDepth()).isEqualTo(depth)
            listOf(result.copy(memoryLimit = result.memoryLimit + 1), result.copy(docLimit = result.docLimit + 1),
                result.copy(rerankTopK = result.rerankTopK + 1), result.copy(rerankFinalK = result.rerankFinalK + 1))
                .forEach { assertThat(it.retrievalDepth()).isNull() }
        }
        assertThat(RagConfiguration().retrievalDepth()).isEqualTo(RetrievalDepth.BALANCED)
    }
}
