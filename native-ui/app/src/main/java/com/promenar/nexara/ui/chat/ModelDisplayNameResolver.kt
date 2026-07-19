package com.promenar.nexara.ui.chat

import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.catalog.ModelCatalogRuntime
import com.promenar.nexara.data.model.catalog.ModelMetadataResolver

internal fun resolveModelDisplayName(
    stableOrRemoteId: String?,
    providerModels: List<ModelInfo>,
    catalogResolver: ModelMetadataResolver = ModelCatalogRuntime.resolver,
): String {
    if (stableOrRemoteId.isNullOrBlank()) return ""

    val remoteId = stableOrRemoteId.substringAfter("::", stableOrRemoteId)
    val providerId = stableOrRemoteId.substringBefore("::", "")
    val stored = providerModels.firstOrNull { model ->
        model.id == stableOrRemoteId ||
            (
                providerId.isNotEmpty() &&
                    model.providerId == providerId &&
                    model.remoteModelId == remoteId
                )
    }

    return stored?.name?.takeIf(String::isNotBlank)
        ?: catalogResolver.resolve(remoteId).displayName
}

internal fun resolveModelDisplayNames(
    sessionModelId: String?,
    messageModelIds: Iterable<String?>,
    providerModels: List<ModelInfo>,
    displayNameResolver: (String, List<ModelInfo>) -> String = { modelId, models ->
        resolveModelDisplayName(modelId, models)
    },
): Map<String, String> = buildSet {
    sessionModelId?.takeIf(String::isNotBlank)?.let(::add)
    messageModelIds.mapNotNullTo(this) { it?.takeIf(String::isNotBlank) }
}.associateWith { modelId ->
    displayNameResolver(modelId, providerModels)
}
