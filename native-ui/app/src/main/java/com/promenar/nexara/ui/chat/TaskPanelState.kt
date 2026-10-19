package com.promenar.nexara.ui.chat

import com.promenar.nexara.data.model.TaskStep

enum class TaskCapsuleMode {
    GENERATING,
    PENDING,
}

enum class TaskPanelStepStatus {
    TODO,
    DOING,
    DONE,
    DROPPED,
}

/** 待办面板清单中的一行；[depth] 为层级缩进，父步骤只作为分组标题出现。 */
data class TaskPanelStep(
    val id: String,
    val title: String,
    val status: TaskPanelStepStatus,
    val depth: Int,
    val isGroup: Boolean,
)

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
    /** 按计划顺序展开的完整清单。 */
    val steps: List<TaskPanelStep> = emptyList(),
    /** 所有叶子步骤均已完成或放弃。 */
    val isCompleted: Boolean = false,
)

fun taskPanelUiState(
    tree: List<TaskStep>,
    isGenerating: Boolean,
    goal: String? = null,
): TaskPanelUiState? {
    val leaves = buildList {
        fun appendLeaves(nodes: List<TaskStep>) {
            nodes.sortedBy(TaskStep::sortOrder).forEach { step ->
                if (step.children.isEmpty()) add(step) else appendLeaves(step.children)
            }
        }
        appendLeaves(tree)
    }
    if (leaves.isEmpty()) return null
    val unfinished = leaves.filter { it.status !in FINISHED_STEP_STATUSES }
    val current = unfinished.firstOrNull { it.status == "doing" } ?: unfinished.firstOrNull()
    val isCompleted = current == null
    return TaskPanelUiState(
        planId = tree.firstOrNull()?.id.orEmpty(),
        title = goal?.takeIf(String::isNotBlank) ?: tree.firstOrNull()?.title.orEmpty(),
        currentStepTitle = current?.title.orEmpty(),
        currentStepId = current?.id.orEmpty(),
        doneCount = leaves.count { it.status == "done" },
        totalCount = leaves.size,
        pendingCount = unfinished.size,
        capsuleMode = if (isGenerating) TaskCapsuleMode.GENERATING else TaskCapsuleMode.PENDING,
        shouldPulse = isGenerating && !isCompleted,
        steps = flattenPanelSteps(tree),
        isCompleted = isCompleted,
    )
}

/**
 * 计划条被隐藏时记住的版本：计划、进度或当前步骤变化后签名不同，计划条重新出现。
 */
fun TaskPanelUiState.visibilitySignature(): String = "$planId:$doneCount/$totalCount:$currentStepId"

/** 计划条是否显示：没有计划不显示；用户隐藏的正是当前版本时不显示。 */
fun isTaskPlanBarVisible(panel: TaskPanelUiState?, hiddenSignature: String?): Boolean =
    panel != null && panel.visibilitySignature() != hiddenSignature

private fun flattenPanelSteps(tree: List<TaskStep>): List<TaskPanelStep> = buildList {
    fun append(nodes: List<TaskStep>, depth: Int) {
        nodes.sortedBy(TaskStep::sortOrder).forEach { step ->
            add(
                TaskPanelStep(
                    id = step.id,
                    title = step.title,
                    status = panelStatus(step.status),
                    depth = depth,
                    isGroup = step.children.isNotEmpty(),
                ),
            )
            append(step.children, depth + 1)
        }
    }
    append(tree, 0)
}

private fun panelStatus(raw: String): TaskPanelStepStatus = when (raw) {
    "done" -> TaskPanelStepStatus.DONE
    "doing" -> TaskPanelStepStatus.DOING
    "dropped" -> TaskPanelStepStatus.DROPPED
    else -> TaskPanelStepStatus.TODO
}
