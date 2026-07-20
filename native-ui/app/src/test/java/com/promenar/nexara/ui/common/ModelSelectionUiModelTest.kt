package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.catalog.ModelCapability
import com.promenar.nexara.data.model.catalog.ModelMetadataResolver
import com.promenar.nexara.data.model.catalog.ModelWorkload
import com.promenar.nexara.data.model.catalog.SupportState
import org.junit.Test

/**
 * Task 7 RED — 锁定冻结的 [ModelSelectionUiModel] 投影工厂合同。
 *
 * 投影工厂先把 [ModelInfo.userEditedFields] 中的 name、type、capabilities、
 * contextLength、maxOutputTokens 转换为 ModelMetadataOverride，再调用
 * ModelMetadataResolver.resolve(remoteModelId, providerId, userOverride = ...)。
 *
 * 见 docs/superpowers/specs/2026-07-20-nexara-md3-convergence-design.md §9。
 */
class ModelSelectionUiModelTest {

    private val resolver = ModelMetadataResolver()

    // ------------------------------------------------------------------
    // 能力三态保留：supported / unsupported / unknown
    // ------------------------------------------------------------------

    @Test
    fun `catalog supported capability resolves as supported in projection`() {
        val projection = model(remoteModelId = "deepseek-v4-flash")
            .toModelSelectionUiModel(resolver)

        assertThat(projection.capabilityStates[ModelCapability.REASONING])
            .isEqualTo(SupportState.SUPPORTED)
    }

    @Test
    fun `catalog unsupported capability resolves as unsupported in projection`() {
        val projection = model(remoteModelId = "gemini-embedding-001")
            .toModelSelectionUiModel(resolver)

        assertThat(projection.capabilityStates[ModelCapability.REASONING])
            .isEqualTo(SupportState.UNSUPPORTED)
    }

    @Test
    fun `non catalog model capability resolves as unknown in projection`() {
        val projection = model(remoteModelId = "vendor-unknown-model-x")
            .toModelSelectionUiModel(resolver)

        assertThat(projection.capabilityStates[ModelCapability.REASONING])
            .isEqualTo(SupportState.UNKNOWN)
    }

    // ------------------------------------------------------------------
    // 冻结投影字段：显示名与上下文取自解析结果
    // ------------------------------------------------------------------

    @Test
    fun `projection uses resolved catalog display name`() {
        val projection = model(remoteModelId = "deepseek-v4-flash")
            .toModelSelectionUiModel(resolver)

        assertThat(projection.displayName).isEqualTo("DeepSeek V4 Flash")
    }

    @Test
    fun `projection uses resolved catalog context tokens`() {
        val projection = model(remoteModelId = "o1-preview")
            .toModelSelectionUiModel(resolver)

        assertThat(projection.contextTokens).isEqualTo(128_000)
    }

    @Test
    fun `non catalog model preserves provider supplied metadata`() {
        val projection = model(
            remoteModelId = "vendor-unknown-model-x",
            name = "Vendor X Pro",
            type = "reasoning",
            contextLength = 262_144,
            capabilities = listOf("vision"),
        ).toModelSelectionUiModel(resolver)

        assertThat(projection.displayName).isEqualTo("Vendor X Pro")
        assertThat(projection.contextTokens).isEqualTo(262_144)
        assertThat(projection.workload).isEqualTo(ModelWorkload.GENERATIVE_TEXT)
        assertThat(projection.capabilityStates[ModelCapability.REASONING])
            .isEqualTo(SupportState.SUPPORTED)
        assertThat(projection.capabilityStates[ModelCapability.VISION_INPUT])
            .isEqualTo(SupportState.SUPPORTED)
    }

    // ------------------------------------------------------------------
    // 用户编辑字段转换为 ModelMetadataOverride
    // ------------------------------------------------------------------

    @Test
    fun `user edited name overrides catalog display name`() {
        val model = model(
            remoteModelId = "deepseek-v4-flash",
            name = "\u6211\u7684 DeepSeek",
            userEditedFields = setOf("name"),
        )

        val projection = model.toModelSelectionUiModel(resolver)

        assertThat(projection.displayName).isEqualTo("\u6211\u7684 DeepSeek")
    }

    @Test
    fun `user edited type maps legacy embedding to embedding workload`() {
        val model = model(
            remoteModelId = "deepseek-v4-flash",
            type = "embedding",
            userEditedFields = setOf("type"),
        )

        val projection = model.toModelSelectionUiModel(resolver)

        assertThat(projection.workload).isEqualTo(ModelWorkload.EMBEDDING)
    }

    @Test
    fun `user edited reasoning type also marks reasoning capability as supported`() {
        val model = model(
            remoteModelId = "minimax-m2.7-highspeed",
            type = "reasoning",
            userEditedFields = setOf("type"),
        )

        val projection = model.toModelSelectionUiModel(resolver)

        assertThat(projection.workload).isEqualTo(ModelWorkload.GENERATIVE_TEXT)
        assertThat(projection.capabilityStates[ModelCapability.REASONING])
            .isEqualTo(SupportState.SUPPORTED)
    }

    @Test
    fun `user edited capabilities mark checked items as supported`() {
        val model = model(
            remoteModelId = "vendor-unknown-model-x",
            capabilities = listOf("reasoning", "vision"),
            userEditedFields = setOf("capabilities"),
        )

        val projection = model.toModelSelectionUiModel(resolver)

        assertThat(projection.capabilityStates[ModelCapability.REASONING])
            .isEqualTo(SupportState.SUPPORTED)
        assertThat(projection.capabilityStates[ModelCapability.VISION_INPUT])
            .isEqualTo(SupportState.SUPPORTED)
    }

    @Test
    fun `user edited capabilities normalize legacy names`() {
        val projection = model(
            remoteModelId = "vendor-unknown-model-x",
            capabilities = listOf("VISION", "AUDIOINPUT"),
            userEditedFields = setOf("capabilities"),
        ).toModelSelectionUiModel(resolver)

        assertThat(projection.capabilityStates[ModelCapability.VISION_INPUT])
            .isEqualTo(SupportState.SUPPORTED)
        assertThat(projection.capabilityStates[ModelCapability.AUDIO_INPUT])
            .isEqualTo(SupportState.SUPPORTED)
    }

    @Test
    fun `user edited capabilities do not mark non editable tool calling as unsupported`() {
        val projection = model(
            remoteModelId = "vendor-unknown-model-x",
            capabilities = emptyList(),
            userEditedFields = setOf("capabilities"),
        ).toModelSelectionUiModel(resolver)

        assertThat(projection.capabilityStates[ModelCapability.TOOL_CALLING])
            .isEqualTo(SupportState.UNKNOWN)
    }

    @Test
    fun `user edited capabilities mark unchecked catalog items as unsupported`() {
        val model = model(
            remoteModelId = "deepseek-v4-flash",
            type = "reasoning",
            capabilities = emptyList(),
            userEditedFields = setOf("type", "capabilities"),
        )

        val projection = model.toModelSelectionUiModel(resolver)

        assertThat(projection.workload).isEqualTo(ModelWorkload.GENERATIVE_TEXT)
        assertThat(projection.capabilityStates[ModelCapability.REASONING])
            .isEqualTo(SupportState.UNSUPPORTED)
    }

    @Test
    fun `user edited context length overrides resolved context tokens`() {
        val model = model(
            remoteModelId = "deepseek-v4-flash",
            contextLength = 999_000,
            userEditedFields = setOf("contextLength"),
        )

        val projection = model.toModelSelectionUiModel(resolver)

        assertThat(projection.contextTokens).isEqualTo(999_000)
    }

    @Test
    fun `user edited max output tokens is included in projection override`() {
        val model = model(
            remoteModelId = "deepseek-v4-flash",
            maxOutputTokens = 8_192,
            userEditedFields = setOf("maxOutputTokens"),
        )

        val override = model.toModelSelectionUserOverride()

        assertThat(override.outputTokens).isEqualTo(8_192)
    }

    // ------------------------------------------------------------------
    // CHAT_ENDPOINT 排除与 chatEndpointCompatible 独立性
    // ------------------------------------------------------------------

    @Test
    fun `CHAT_ENDPOINT is excluded from capability states`() {
        val projection = model(remoteModelId = "deepseek-v4-flash")
            .toModelSelectionUiModel(resolver)

        assertThat(projection.capabilityStates.keys).doesNotContain(ModelCapability.CHAT_ENDPOINT)
    }

    @Test
    fun `chatEndpointCompatible is taken from model info`() {
        val model = model(
            remoteModelId = "deepseek-v4-flash",
            chatEndpointCompatible = SupportState.SUPPORTED,
        )

        val projection = model.toModelSelectionUiModel(resolver)

        assertThat(projection.chatEndpointCompatible).isEqualTo(SupportState.SUPPORTED)
    }

    @Test
    fun `chatEndpointCompatible stays unknown even when workload is generative text and reasoning is supported`() {
        val model = model(
            remoteModelId = "deepseek-v4-flash",
            chatEndpointCompatible = SupportState.UNKNOWN,
        )

        val projection = model.toModelSelectionUiModel(resolver)

        assertThat(projection.workload).isEqualTo(ModelWorkload.GENERATIVE_TEXT)
        assertThat(projection.capabilityStates[ModelCapability.REASONING])
            .isEqualTo(SupportState.SUPPORTED)
        assertThat(projection.chatEndpointCompatible).isEqualTo(SupportState.UNKNOWN)
    }

    @Test
    fun `chat picker accepts explicit generative workload with unknown endpoint`() {
        val projection = model(
            remoteModelId = "vendor-unknown-chat",
            type = "chat",
        ).toModelSelectionUiModel(resolver)

        assertThat(projection.chatEndpointCompatible).isEqualTo(SupportState.UNKNOWN)
        assertThat(projection.matchesModelPickerFilter("chat")).isTrue()
    }

    @Test
    fun `chat picker does not infer endpoint compatibility from reasoning capability alone`() {
        val projection = model(
            remoteModelId = "vendor-unknown-embedding",
            type = "embedding",
            capabilities = listOf("reasoning"),
        ).toModelSelectionUiModel(resolver)

        assertThat(projection.workload).isEqualTo(ModelWorkload.EMBEDDING)
        assertThat(projection.capabilityStates[ModelCapability.REASONING])
            .isEqualTo(SupportState.SUPPORTED)
        assertThat(projection.matchesModelPickerFilter("chat")).isFalse()
    }

    @Test
    fun `chat picker rejects explicit unsupported endpoint even for generative workload`() {
        val projection = model(
            remoteModelId = "vendor-unsupported-chat",
            type = "chat",
            chatEndpointCompatible = SupportState.UNSUPPORTED,
        ).toModelSelectionUiModel(resolver)

        assertThat(projection.workload).isEqualTo(ModelWorkload.GENERATIVE_TEXT)
        assertThat(projection.matchesModelPickerFilter("chat")).isFalse()
    }

    @Test
    fun `reasoning type matching is case insensitive`() {
        val projection = model(
            remoteModelId = "vendor-reasoning-case",
            type = "Reasoning",
            userEditedFields = setOf("type"),
        ).toModelSelectionUiModel(resolver)

        assertThat(projection.capabilityStates[ModelCapability.REASONING])
            .isEqualTo(SupportState.SUPPORTED)
    }

    // ------------------------------------------------------------------
    // 空能力不得制造 CHAT
    // ------------------------------------------------------------------

    @Test
    fun `empty capabilities do not manufacture chat endpoint or supported states`() {
        val model = model(
            remoteModelId = "vendor-unknown-model-x",
            capabilities = emptyList(),
            userEditedFields = setOf("capabilities"),
        )

        val projection = model.toModelSelectionUiModel(resolver)

        assertThat(projection.capabilityStates.keys).doesNotContain(ModelCapability.CHAT_ENDPOINT)
        assertThat(projection.chatEndpointCompatible).isEqualTo(SupportState.UNKNOWN)
        assertThat(projection.capabilityStates.values.none { it == SupportState.SUPPORTED }).isTrue()
    }

    // ------------------------------------------------------------------
    // 身份字段：selectionId / remoteModelId / providerName
    // ------------------------------------------------------------------

    @Test
    fun `selection id provider name and remote model id come from model info`() {
        val model = model(
            id = "provider-a::vendor-model-x",
            remoteModelId = "vendor-model-x",
            providerId = "provider-a",
            providerName = "Provider A",
        )

        val projection = model.toModelSelectionUiModel(resolver)

        assertThat(projection.selectionId).isEqualTo("provider-a::vendor-model-x")
        assertThat(projection.remoteModelId).isEqualTo("vendor-model-x")
        assertThat(projection.providerName).isEqualTo("Provider A")
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private fun model(
        id: String = "default::model",
        remoteModelId: String = "model",
        providerId: String? = "default",
        providerName: String = "Default",
        name: String = remoteModelId,
        type: String = "unknown",
        contextLength: Int = 0,
        capabilities: List<String> = emptyList(),
        maxOutputTokens: Int = 0,
        chatEndpointCompatible: SupportState = SupportState.UNKNOWN,
        userEditedFields: Set<String> = emptySet(),
    ): ModelInfo = ModelInfo(
        name = name,
        id = id,
        description = "",
        enabled = true,
        type = type,
        contextLength = contextLength,
        capabilities = capabilities,
        providerName = providerName,
        providerId = providerId,
        remoteModelId = remoteModelId,
        maxOutputTokens = maxOutputTokens,
        chatEndpointCompatible = chatEndpointCompatible,
        userEditedFields = userEditedFields,
    )
}
