package com.promenar.infinity.domain.repository

import com.promenar.infinity.data.model.TaskState
import com.promenar.infinity.data.model.TaskStep
import kotlinx.coroutines.flow.Flow

interface ITaskRepository {
    fun observeActiveTree(sessionId: String): Flow<List<TaskStep>>
    /** 活跃计划的目标；没有活跃计划或计划未记录目标时为 null。 */
    fun observeGoal(sessionId: String): Flow<String?> = kotlinx.coroutines.flow.flowOf(null)
    suspend fun initializePlan(sessionId: String, goal: String, tree: List<TaskStep>): TaskState
    suspend fun updatePlan(sessionId: String, operations: List<PlanPatchOp>): TaskState
    suspend fun getPlan(sessionId: String): TaskState?
    suspend fun dropPlan(sessionId: String, reason: String)

    fun deriveParentStatus(children: List<TaskStep>): String
    fun countLeafProgress(steps: List<TaskStep>): Pair<Int, Int>
}

data class PlanPatchOp(
    val action: String,
    val stepId: String? = null,
    val parentId: String? = null,
    val payload: Map<String, String>? = null
)
