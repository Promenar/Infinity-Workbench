package com.promenar.nexara.ui.welcome

import com.promenar.nexara.ui.settings.ModelInfo

internal fun eligibleOnboardingModels(
    models: List<ModelInfo>,
    providerId: String?,
): List<ModelInfo> = models.filter { model ->
    val type = model.type.lowercase()
    model.providerId == providerId &&
        type in setOf("chat", "reasoning", "unknown")
}
