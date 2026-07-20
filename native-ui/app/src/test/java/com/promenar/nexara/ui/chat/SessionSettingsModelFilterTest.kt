package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.catalog.ModelCapability
import com.promenar.nexara.data.model.catalog.ModelMetadataResolver
import com.promenar.nexara.data.model.catalog.ModelWorkload
import com.promenar.nexara.data.model.catalog.SupportState
import com.promenar.nexara.ui.common.toModelSelectionUiModel
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

    // ------------------------------------------------------------------
    // Task 7 — 会话过滤后投影不得制造 CHAT
    // ------------------------------------------------------------------

    @Test
    fun `空能力模型经会话过滤投影后不制造 CHAT 端点支持`() {
        val models = listOf(
            model(
                id = "cloud::vendor-unknown-model-x",
                providerId = "cloud",
                type = "unknown",
                capabilities = emptyList(),
            ),
        )
        val resolver = ModelMetadataResolver()

        val visible = filterLocalInferenceModels(models, setOf("local"), localInferenceAvailable = false)
        val projected = visible.map { it.toModelSelectionUiModel(resolver) }

        assertThat(projected).hasSize(1)
        assertThat(projected[0].capabilityStates.keys).doesNotContain(ModelCapability.CHAT_ENDPOINT)
        assertThat(projected[0].chatEndpointCompatible).isEqualTo(SupportState.UNKNOWN)
        assertThat(projected[0].capabilityStates.values.none { it == SupportState.SUPPORTED }).isTrue()
    }

    @Test
    fun `会话模型列表不把未知模型推断为可对话模型`() {
        val unknown = model(
            id = "cloud::vendor-unknown-model-x",
            providerId = "cloud",
        ).toModelSelectionUiModel(ModelMetadataResolver())

        assertThat(filterSessionSelectionModels(listOf(unknown), selectedModelId = ""))
            .isEmpty()
    }

    @Test
    fun `会话模型列表保留显式生成式模型和当前已选未知模型`() {
        val resolver = ModelMetadataResolver()
        val generative = model(
            id = "cloud::custom-chat",
            providerId = "cloud",
            type = "chat",
        ).toModelSelectionUiModel(resolver)
        val selectedUnknown = model(
            id = "cloud::selected-legacy",
            providerId = "cloud",
        ).toModelSelectionUiModel(resolver)

        val visible = filterSessionSelectionModels(
            models = listOf(generative, selectedUnknown),
            selectedModelId = selectedUnknown.selectionId,
        )

        assertThat(generative.workload).isEqualTo(ModelWorkload.GENERATIVE_TEXT)
        assertThat(visible).containsExactly(generative, selectedUnknown).inOrder()
    }

    @Test
    fun `会话模型列表排除明确不支持 Chat endpoint 的生成式模型`() {
        val unsupported = model(
            id = "cloud::unsupported-chat",
            providerId = "cloud",
            type = "chat",
            chatEndpointCompatible = SupportState.UNSUPPORTED,
        ).toModelSelectionUiModel(ModelMetadataResolver())

        assertThat(filterSessionSelectionModels(listOf(unsupported), selectedModelId = ""))
            .isEmpty()
        assertThat(filterSessionSelectionModels(listOf(unsupported), selectedModelId = unsupported.selectionId))
            .containsExactly(unsupported)
    }

    @Test
    fun `会话过滤保留 catalog 推理能力三态投影`() {
        val models = listOf(
            model(
                id = "cloud::deepseek-v4-flash",
                providerId = "cloud",
                remoteModelId = "deepseek-v4-flash",
            ),
            model("local::gguf", providerId = "local"),
        )
        val resolver = ModelMetadataResolver()

        val visible = filterLocalInferenceModels(models, setOf("local"), localInferenceAvailable = false)
        val projected = visible.map { it.toModelSelectionUiModel(resolver) }

        assertThat(projected).hasSize(1)
        assertThat(projected[0].capabilityStates[ModelCapability.REASONING])
            .isEqualTo(SupportState.SUPPORTED)
    }

    private fun model(
        id: String,
        providerId: String?,
        remoteModelId: String = id.substringAfter("::", id),
        name: String = id,
        type: String = "unknown",
        capabilities: List<String> = emptyList(),
        chatEndpointCompatible: SupportState = SupportState.UNKNOWN,
    ): ModelInfo = ModelInfo(
        name = name,
        id = id,
        description = "",
        enabled = true,
        type = type,
        capabilities = capabilities,
        providerId = providerId,
        remoteModelId = remoteModelId,
        chatEndpointCompatible = chatEndpointCompatible,
    )
}
