package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import com.promenar.nexara.ui.welcome.eligibleOnboardingModels

class SettingsViewModelModelClassificationTest {
    @Test
    fun `未知 embedding 与 rerank ID 不得默认归类为 chat`() {
        val embeddingType = classifyFetchedModelType("foo-embedding", null)
        assertThat(embeddingType)
            .isEqualTo("embedding")
        assertThat(classifyFetchedModelType("vendor/bge-reranker-v2", null))
            .isEqualTo("rerank")
        val embedding = ModelInfo(
            name = "foo-embedding",
            id = "provider::foo-embedding",
            remoteModelId = "foo-embedding",
            description = "",
            enabled = false,
            type = embeddingType,
            providerId = "provider",
        )
        assertThat(eligibleOnboardingModels(listOf(embedding), "provider")).isEmpty()
    }

    @Test
    fun `未知聚合聊天模型在真实探测前保持 unknown`() {
        val remoteId = "aggregator/new-intelligence-model"

        assertThat(classifyFetchedModelType(remoteId, null)).isEqualTo("unknown")
    }

    @Test
    fun `模型规格元数据优先于名称启发式`() {
        assertThat(classifyFetchedModelType("vendor/custom", "reasoning"))
            .isEqualTo("reasoning")
    }
}
