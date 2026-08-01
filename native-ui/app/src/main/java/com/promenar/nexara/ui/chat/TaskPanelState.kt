package com.promenar.nexara.ui.chat

import com.promenar.nexara.data.model.TaskStep

enum class TaskCapsuleMode {
    GENERATING,
    PENDING,
}

data class TaskPanelUiState(
    val planId: String,
    val title: String,
    val currentStepTitle: String,
    val currentStepId: String,
    val doneCount: Int,
    val totalCount: Int,
    val pendingCount: Int,
    val capsuleMode: TaskCapsuleMode,
    val shouldPulse: Boolean,
)

fun taskPanelUiState(
    tree: List<TaskStep>,
    isGenerating: Boolean,
): TaskPanelUiState? {
    val leaves = buildList {
        fun appendLeaves(nodes: List<TaskStep>) {
            nodes.sortedBy(TaskStep::sortOrder).forEach { step ->
                if (step.children.isEmpty()) add(step) else appendLeaves(step.children)
            }
        }
        appendLeaves(tree)
    }
    val unfinished = leaves.filter { it.status !in setOf("done", "dropped") }
    if (unfinished.isEmpty()) return null

    val current = unfinished.firstOrNull { it.status == "doing" } ?: unfinished.first()
    return TaskPanelUiState(
        planId = tree.firstOrNull()?.id.orEmpty(),
        title = tree.firstOrNull()?.title.orEmpty(),
        currentStepTitle = current.title,
        currentStepId = current.id,
        doneCount = leaves.count { it.status == "done" },
        totalCount = leaves.size,
        pendingCount = unfinished.size,
        capsuleMode = if (isGenerating) TaskCapsuleMode.GENERATING else TaskCapsuleMode.PENDING,
        shouldPulse = isGenerating,
    )
}

fun unfinishedTaskLeafIds(tree: List<TaskStep>): List<String> = buildList {
    fun append(nodes: List<TaskStep>) {
        nodes.sortedBy(TaskStep::sortOrder).forEach { step ->
            if (step.children.isEmpty()) {
                if (step.status !in setOf("done", "dropped")) add(step.id)
            } else {
                append(step.children)
            }
        }
    }
    append(tree)
}
