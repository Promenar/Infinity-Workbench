package com.promenar.nexara.ui.welcome

import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.catalog.SupportState

internal fun onboardingModelCandidates(
    models: List<ModelInfo>,
    providerId: String?,
): List<ModelInfo> = models.filter { model ->
    val type = model.type.lowercase()
    model.providerId == providerId && (
        type in setOf("chat", "reasoning", "unknown") ||
            model.chatEndpointCompatible == SupportState.SUPPORTED
    )
}

internal fun eligibleOnboardingModels(
    models: List<ModelInfo>,
    providerId: String?,
): List<ModelInfo> = models.filter { model ->
    val type = model.type.lowercase()
    model.providerId == providerId && (
        type in setOf("chat", "reasoning") ||
            model.chatEndpointCompatible == SupportState.SUPPORTED
    )
}
