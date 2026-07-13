package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.settings.ModelInfo
import org.junit.Test

class SessionSettingsModelFilterTest {
    @Test
    fun `Release 隐藏恢复的 Local Provider 模型并保留云端模型`() {
        val models = listOf(
            model("local::gguf", providerId = "local"),
            model("cloud::chat", providerId = "cloud"),
        )

        val visible = filterLocalInferenceModels(
            models = models,
            localProviderIds = setOf("local"),
            localInferenceAvailable = false,
        )

        assertThat(visible.map { it.id }).containsExactly("cloud::chat")
    }

    @Test
    fun `缺少 providerId 的旧模型按稳定复合标识识别 Local 归属`() {
        val models = listOf(
            model("restored-local::model.gguf", providerId = null),
            model("cloud::chat", providerId = null),
        )

        val visible = filterLocalInferenceModels(
            models = models,
            localProviderIds = setOf("restored-local"),
            localInferenceAvailable = false,
        )

        assertThat(visible.map { it.id }).containsExactly("cloud::chat")
    }

    @Test
    fun `Debug 能力可用时不改变模型集合与顺序`() {
        val models = listOf(
            model("local::gguf", providerId = "local"),
            model("cloud::chat", providerId = "cloud"),
        )

        assertThat(filterLocalInferenceModels(models, setOf("local"), true))
            .containsExactlyElementsIn(models)
            .inOrder()
    }

    private fun model(id: String, providerId: String?) = ModelInfo(
        name = id,
        id = id,
        description = "",
        enabled = true,
        providerId = providerId,
    )
}
