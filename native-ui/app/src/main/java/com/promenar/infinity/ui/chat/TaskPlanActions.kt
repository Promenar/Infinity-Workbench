package com.promenar.infinity.ui.chat

import com.promenar.infinity.data.model.TaskStep
import com.promenar.infinity.domain.repository.ITaskRepository
import com.promenar.infinity.domain.repository.PlanPatchOp

/** 用户在待办面板上直接对计划执行的操作；模型侧的计划修改走计划工具。 */
class TaskPlanActions(private val repository: ITaskRepository) {

    /**
     * 把所有未完成的叶子步骤标记为完成。返回这些步骤原来的状态，供撤销时恢复；
     * 没有活跃计划或已全部完成时返回 null。
     */
    suspend fun completeRemaining(sessionId: String): Map<String, String>? {
        val plan = repository.getPlan(sessionId) ?: return null
        val previous = unfinishedLeaves(plan.steps).associate { it.id to it.status }
        if (previous.isEmpty()) return null
        repository.updatePlan(sessionId, previous.keys.map { statusOp(it, DONE) })
        return previous
    }

    /** 按 [completeRemaining] 返回的原状态恢复；进行中的步骤最后恢复，保证只有它处于进行中。 */
    suspend fun restore(sessionId: String, previous: Map<String, String>) {
        if (previous.isEmpty()) return
        val ordered = previous.entries.sortedBy { it.value == DOING }
        repository.updatePlan(sessionId, ordered.map { (stepId, status) -> statusOp(stepId, status) })
    }

    /** 清除已全部完成的计划；计划仍有未完成步骤时不做任何事并返回 false。 */
    suspend fun clearCompleted(sessionId: String): Boolean {
        val plan = repository.getPlan(sessionId) ?: return false
        if (unfinishedLeaves(plan.steps).isNotEmpty()) return false
        repository.dropPlan(sessionId, "user cleared completed plan")
        return true
    }

    private fun statusOp(stepId: String, status: String) = PlanPatchOp(
        action = "set_status",
        stepId = stepId,
        payload = mapOf("status" to status),
    )

    private companion object {
        const val DONE = "done"
        const val DOING = "doing"
    }
}

/** 按排序展开的叶子步骤中尚未完成（非完成、非放弃）的部分。 */
internal fun unfinishedLeaves(tree: List<TaskStep>): List<TaskStep> = buildList {
    fun append(nodes: List<TaskStep>) {
        nodes.sortedBy(TaskStep::sortOrder).forEach { step ->
            if (step.children.isEmpty()) {
                if (step.status !in FINISHED_STEP_STATUSES) add(step)
            } else {
                append(step.children)
            }
        }
    }
    append(tree)
}

internal val FINISHED_STEP_STATUSES = setOf("done", "dropped")
