package com.promenar.infinity.ui.welcome

import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.catalog.SupportState

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
