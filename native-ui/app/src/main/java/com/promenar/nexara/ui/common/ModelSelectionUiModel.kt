package com.promenar.nexara.ui.common

import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.catalog.ModelCapability
import com.promenar.nexara.data.model.catalog.ModelMetadataOverride
import com.promenar.nexara.data.model.catalog.ModelMetadataResolver
import com.promenar.nexara.data.model.catalog.ModelWorkload
import com.promenar.nexara.data.model.catalog.SupportState

data class ModelSelectionUiModel(
    val selectionId: String,
    val remoteModelId: String,
    val displayName: String,
    val providerName: String,
    val contextTokens: Int?,
    val workload: ModelWorkload,
    val capabilityStates: Map<ModelCapability, SupportState>,
    val chatEndpointCompatible: SupportState,
)

private fun ModelCapability.toLegacyName(): String? = when (this) {
    ModelCapability.REASONING -> "reasoning"
    ModelCapability.VISION_INPUT -> "vision"
    ModelCapability.AUDIO_INPUT -> "audioinput"
    ModelCapability.AUDIO_OUTPUT -> "audiooutput"
    ModelCapability.VIDEO_INPUT -> "videounderstanding"
    ModelCapability.STRUCTURED_OUTPUT -> "structuredoutput"
    ModelCapability.PROMPT_CACHING -> "promptcaching"
    ModelCapability.COMPUTER_USE -> "computeruse"
    ModelCapability.WEB_ACCESS -> "internet"
    else -> null
}

private fun String.toModelWorkload(): ModelWorkload? = when (lowercase()) {
    "reasoning", "chat" -> ModelWorkload.GENERATIVE_TEXT
    "embedding" -> ModelWorkload.EMBEDDING
    "rerank" -> ModelWorkload.RERANK
    "image" -> ModelWorkload.IMAGE_GENERATION
    "audio" -> ModelWorkload.AUDIO
    "video" -> ModelWorkload.VIDEO
    else -> null
}

private fun ModelInfo.toModelSelectionProviderMetadata(): ModelMetadataOverride {
    val normalizedCapabilities = capabilities.mapTo(mutableSetOf()) { it.lowercase() }
    val supportedCapabilities = buildMap {
        if (type.equals("reasoning", ignoreCase = true)) {
            put(ModelCapability.REASONING, SupportState.SUPPORTED)
        }
        ModelCapability.values().forEach { capability ->
            val legacyName = capability.toLegacyName() ?: return@forEach
            if (legacyName in normalizedCapabilities) {
                put(capability, SupportState.SUPPORTED)
            }
        }
    }

    return ModelMetadataOverride(
        displayName = name.takeIf {
            it.isNotBlank() && it != id && it != remoteModelId
        },
        workload = type.toModelWorkload(),
        capabilities = supportedCapabilities,
        contextTokens = contextLength.takeIf { it > 0 },
        outputTokens = maxOutputTokens.takeIf { it > 0 },
    )
}

fun ModelInfo.toModelSelectionUserOverride(): ModelMetadataOverride {
    val displayNameOverride = if ("name" in userEditedFields) name else null
    val contextTokensOverride = if ("contextLength" in userEditedFields) contextLength else null
    val outputTokensOverride = if ("maxOutputTokens" in userEditedFields) maxOutputTokens else null

    val workloadOverride = if ("type" in userEditedFields) {
        type.toModelWorkload() ?: ModelWorkload.UNKNOWN
    } else null

    val capabilitiesMap = mutableMapOf<ModelCapability, SupportState>()
    if ("type" in userEditedFields) {
        if (type.equals("reasoning", ignoreCase = true)) {
            capabilitiesMap[ModelCapability.REASONING] = SupportState.SUPPORTED
        }
    }
    if ("capabilities" in userEditedFields) {
        val normalizedCapabilities = capabilities.mapTo(mutableSetOf()) { it.lowercase() }
        for (cap in ModelCapability.values()) {
            val legacyName = cap.toLegacyName() ?: continue
            capabilitiesMap[cap] = if (legacyName in normalizedCapabilities) {
                SupportState.SUPPORTED
            } else SupportState.UNSUPPORTED
        }
    }

    return ModelMetadataOverride(
        displayName = displayNameOverride,
        workload = workloadOverride,
        capabilities = capabilitiesMap,
        contextTokens = contextTokensOverride,
        outputTokens = outputTokensOverride
    )
}

internal fun ModelSelectionUiModel.isChatSelectionCandidate(): Boolean = when (chatEndpointCompatible) {
    SupportState.SUPPORTED -> true
    SupportState.UNSUPPORTED -> false
    SupportState.UNKNOWN -> workload == ModelWorkload.GENERATIVE_TEXT
}

fun ModelInfo.toModelSelectionUiModel(resolver: ModelMetadataResolver): ModelSelectionUiModel {
    val userOverride = toModelSelectionUserOverride()
    val resolved = resolver.resolve(
        remoteModelId = remoteModelId,
        providerId = providerId,
        providerMetadata = toModelSelectionProviderMetadata(),
        userOverride = userOverride
    )

    val capabilityStates = resolved.capabilities.filterKeys { it != ModelCapability.CHAT_ENDPOINT }

    return ModelSelectionUiModel(
        selectionId = id,
        remoteModelId = remoteModelId,
        displayName = resolved.displayName,
        providerName = providerName,
        contextTokens = resolved.contextTokens,
        workload = resolved.workload,
        capabilityStates = capabilityStates,
        chatEndpointCompatible = chatEndpointCompatible
    )
}
