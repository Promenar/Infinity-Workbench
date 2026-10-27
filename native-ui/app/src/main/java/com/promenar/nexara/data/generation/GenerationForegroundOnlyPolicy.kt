package com.promenar.nexara.data.generation

import com.promenar.nexara.domain.generation.CancellationReason
import com.promenar.nexara.domain.generation.GenerationCoordinator
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationRuntimePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 离开前台时只停止未获后台执行许可的任务，包括尚未开始的排队任务。 */
internal fun observeForegroundOnlyPolicy(
    scope: CoroutineScope,
    foreground: Flow<Boolean>,
    coordinator: GenerationCoordinator,
) = scope.launch {
    combine(foreground, coordinator.sessions) { visible, sessions -> visible to sessions }
        .collect { (visible, sessions) ->
            if (!visible && sessions.values.any {
                it.runtimePolicy == GenerationRuntimePolicy.FOREGROUND_ONLY && it.phase !in FINISHED_PHASES
            }) coordinator.cancelForPolicy(GenerationRuntimePolicy.FOREGROUND_ONLY, CancellationReason.BACKGROUND_UNAVAILABLE)
        }
}

private val FINISHED_PHASES = setOf(
    GenerationPhase.COMPLETED, GenerationPhase.FAILED, GenerationPhase.CANCELLED,
    GenerationPhase.PERSISTENCE_FAILED, GenerationPhase.WAITING_APPROVAL, GenerationPhase.WAITING_INPUT,
)
