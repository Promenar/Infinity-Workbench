package com.promenar.nexara.ui.settings

import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.withRecordedUserEdits
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

    @Test
    fun `模型管理提交只记录实际变化的五个用户字段`() {
        val current = ModelInfo(
            name = "模型",
            id = "provider::model",
            description = "",
            enabled = true,
            type = "chat",
            contextLength = 4096,
            capabilities = listOf("chat", "vision"),
            maxOutputTokens = 1024,
        )
        val submitted = current.copy(
            name = "我的模型",
            capabilities = listOf("vision", "chat"),
            maxOutputTokens = 2048,
        )

        val marked = submitted.withRecordedUserEdits(current)

        assertThat(marked.userEditedFields).containsExactly("name", "maxOutputTokens")
    }
}
