package com.promenar.nexara.background.generation

import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot

sealed interface GenerationForegroundDecision {
    data class Update(val snapshot: GenerationTaskSnapshot) : GenerationForegroundDecision
    data class Stop(val taskId: String) : GenerationForegroundDecision
}

fun reduceGenerationForeground(
    trackedTaskId: String,
    snapshot: GenerationTaskSnapshot?,
): GenerationForegroundDecision {
    if (snapshot?.taskId != trackedTaskId) return GenerationForegroundDecision.Stop(trackedTaskId)
    return if (snapshot.phase in STOPPING_PHASES) {
        GenerationForegroundDecision.Stop(trackedTaskId)
    } else {
        GenerationForegroundDecision.Update(snapshot)
    }
}

fun shouldHandleGenerationStop(trackedTaskId: String?, requestedTaskId: String): Boolean =
    trackedTaskId == requestedTaskId

private val STOPPING_PHASES = setOf(
    GenerationPhase.WAITING_APPROVAL,
    GenerationPhase.COMPLETED,
    GenerationPhase.FAILED,
    GenerationPhase.CANCELLED,
    GenerationPhase.PERSISTENCE_FAILED,
)
