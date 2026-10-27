package com.promenar.nexara.background.generation

import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot

sealed interface GenerationForegroundDecision {
    data class Update(val snapshot: GenerationTaskSnapshot) : GenerationForegroundDecision
    data class UpdateMany(val snapshots: List<GenerationTaskSnapshot>) : GenerationForegroundDecision
    data class Stop(val taskId: String) : GenerationForegroundDecision
}

fun reduceGenerationForeground(sessions: Map<String, GenerationTaskSnapshot>): GenerationForegroundDecision {
    val snapshots = sessions.values.filter { it.phase !in STOPPING_PHASES }
    return if (snapshots.isEmpty()) GenerationForegroundDecision.Stop("")
    else GenerationForegroundDecision.UpdateMany(snapshots)
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
    GenerationPhase.WAITING_INPUT,
    GenerationPhase.COMPLETED,
    GenerationPhase.FAILED,
    GenerationPhase.CANCELLED,
    GenerationPhase.PERSISTENCE_FAILED,
)
