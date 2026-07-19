package com.promenar.nexara.ui.welcome

import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.catalog.SupportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun runOnboardingEndpointProbe(
    timeoutMillis: Long = 15_000,
    request: suspend () -> Unit,
): Boolean = try {
    withTimeoutOrNull(timeoutMillis) {
        request()
        true
    } ?: false
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    false
}

/** 已知 chat/reasoning 不调用探测直接确认端点；unknown 探测成功后只写入端点兼容。 */
internal suspend fun verifyOnboardingModelCandidate(
    model: ModelInfo,
    probe: suspend (String) -> Boolean,
): ModelInfo? = when {
    model.type.lowercase() in setOf("chat", "reasoning") -> model.copy(
        chatEndpointCompatible = SupportState.SUPPORTED,
    )

    model.chatEndpointCompatible == SupportState.SUPPORTED -> model

    model.type.lowercase() == "unknown" && probe(model.remoteModelId) -> model.copy(
        chatEndpointCompatible = SupportState.SUPPORTED,
    )

    else -> null
}
