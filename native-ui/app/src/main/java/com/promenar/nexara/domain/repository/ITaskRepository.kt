package com.promenar.nexara.domain.repository

import com.promenar.nexara.data.model.TaskState
import com.promenar.nexara.data.model.TaskStep
import kotlinx.coroutines.flow.Flow

interface ITaskRepository {
    fun observeActiveTree(sessionId: String): Flow<List<TaskStep>>
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
