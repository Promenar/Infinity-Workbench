package com.promenar.nexara.ui.welcome

import com.promenar.nexara.ui.settings.ModelInfo

/** 已知聊天模型直接通过；unknown 候选必须由针对该 remoteModelId 的真实最小请求证明。 */
internal suspend fun verifyOnboardingModelCandidate(
    model: ModelInfo,
    probe: suspend (String) -> Boolean,
): ModelInfo? = when (model.type.lowercase()) {
    "chat", "reasoning" -> model
    "unknown" -> if (probe(model.remoteModelId)) {
        model.copy(type = "chat", capabilities = (model.capabilities + "chat").distinct())
    } else {
        null
    }
    else -> null
}
