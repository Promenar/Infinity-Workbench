package com.promenar.infinity.ui.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.data.rag.RetrievalDepth
import com.promenar.infinity.data.rag.withRetrievalDepth
import org.junit.Test

class KnowledgeBaseSettingsLogicTest {

    @Test
    fun `匹配档位时只有三段并选中该档位`() {
        RetrievalDepth.entries.forEach { depth ->
            val config = RagConfiguration().withRetrievalDepth(depth)
            assertThat(retrievalDepthChoices(config))
                .containsExactlyElementsIn(RetrievalDepth.entries.map { RetrievalDepthChoice.Level(it) }).inOrder()
            assertThat(selectedRetrievalDepthChoice(config)).isEqualTo(RetrievalDepthChoice.Level(depth))
        }
    }

    @Test
    fun `任一原始值偏离档位时追加自定义段并选中它`() {
        val config = RagConfiguration().withRetrievalDepth(RetrievalDepth.BALANCED).copy(rerankFinalK = 7)

        assertThat(retrievalDepthChoices(config).last()).isEqualTo(RetrievalDepthChoice.Custom)
        assertThat(retrievalDepthChoices(config)).hasSize(RetrievalDepth.entries.size + 1)
        assertThat(selectedRetrievalDepthChoice(config)).isEqualTo(RetrievalDepthChoice.Custom)
        // 选回档位后自定义段消失。
        val restored = config.withRetrievalDepth(RetrievalDepth.LEAN)
        assertThat(retrievalDepthChoices(restored)).doesNotContain(RetrievalDepthChoice.Custom)
    }

    @Test
    fun `改写变体数只在开启改写且为多查询策略时可调`() {
        val base = RagConfiguration()
        assertThat(rewriteVariantsEnabled(base.copy(enableQueryRewrite = true, queryRewriteStrategy = "multi-query"))).isTrue()
        assertThat(rewriteVariantsEnabled(base.copy(enableQueryRewrite = false, queryRewriteStrategy = "multi-query"))).isFalse()
        assertThat(rewriteVariantsEnabled(base.copy(enableQueryRewrite = true, queryRewriteStrategy = "hyde"))).isFalse()
    }

    @Test
    fun `分块参数与建索引基线不同时才提示重建`() {
        val config = RagConfiguration()
        assertThat(chunkSettingsChanged(config, indexed = null)).isFalse()
        assertThat(chunkSettingsChanged(config, config.chunkSettings())).isFalse()
        assertThat(chunkSettingsChanged(config.copy(docChunkSize = config.docChunkSize + 100), config.chunkSettings())).isTrue()
        assertThat(chunkSettingsChanged(config.copy(chunkOverlap = config.chunkOverlap + 10), config.chunkSettings())).isTrue()
        assertThat(chunkSettingsChanged(config.copy(memoryChunkSize = config.memoryChunkSize + 100), config.chunkSettings())).isTrue()
        // 与分块无关的参数不触发提示。
        assertThat(chunkSettingsChanged(config.copy(docLimit = config.docLimit + 1), config.chunkSettings())).isFalse()
    }

    @Test
    fun `模型行未设置显示跟随，已删除的模型显示原始标识`() {
        val models = listOf(ModelInfo(name = "Fast One", id = "p:fast", description = "", enabled = true))
        assertThat(knowledgeModelLabel(null, models)).isNull()
        assertThat(knowledgeModelLabel(" ", models)).isNull()
        assertThat(knowledgeModelLabel("p:fast", models)).isEqualTo("Fast One")
        assertThat(knowledgeModelLabel("p:gone", models)).isEqualTo("p:gone")
    }
}
