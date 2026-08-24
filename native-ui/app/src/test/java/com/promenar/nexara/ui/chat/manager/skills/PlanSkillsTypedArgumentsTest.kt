package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.TaskState
import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.domain.repository.ITaskRepository
import com.promenar.nexara.domain.repository.PlanPatchOp
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class PlanSkillsTypedArgumentsTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "session-1"
        override val agentId = "agent-1"
        override val workspacePath = null
        override val workspaceRootUuid = "root-1"
    }

    @Test
    fun `update plan 直接消费嵌套 JsonObject`() = runTest {
        val repo = RecordingTaskRepository()
        val args = Json.parseToJsonElement(
            """{
              "operations":[{
                "action":"add_step",
                "parentId":"parent",
                "payload":{"title":"子步骤","sortOrder":"2","note":"保留"}
              }]
            }""".trimIndent(),
        ).jsonObject

        val result = UpdatePlanSkill(repo).execute(args, context)

        assertThat(result.status).isEqualTo("success")
        assertThat(repo.updated).containsExactly(
            PlanPatchOp(
                action = "add_step",
                parentId = "parent",
                payload = mapOf(
                    "title" to "子步骤",
                    "sortOrder" to "2",
                    "note" to "保留",
                ),
            ),
        )
    }

    @Test
    fun `initialize plan 直接消费递归 tree 数组并保持整数`() = runTest {
        val repo = RecordingTaskRepository()
        val args = Json.parseToJsonElement(
            """{
              "goal":"发布",
              "tree":[{
                "id":"root",
                "title":"根步骤",
                "sortOrder":1,
                "children":[{"id":"leaf","title":"叶步骤","sortOrder":3,"children":[]}]
              }]
            }""".trimIndent(),
        ).jsonObject

        val result = InitializePlanSkill(repo).execute(args, context)

        assertThat(result.status).isEqualTo("success")
        assertThat(repo.initializedGoal).isEqualTo("发布")
        assertThat(repo.initializedTree.single().sortOrder).isEqualTo(1)
        assertThat(repo.initializedTree.single().children.single().sortOrder).isEqualTo(3)
    }

    private class RecordingTaskRepository : ITaskRepository {
        var updated: List<PlanPatchOp> = emptyList()
        var initializedGoal: String? = null
        var initializedTree: List<TaskStep> = emptyList()

        override fun observeActiveTree(sessionId: String): Flow<List<TaskStep>> = emptyFlow()
        override suspend fun initializePlan(
            sessionId: String,
            goal: String,
            tree: List<TaskStep>,
        ): TaskState {
            initializedGoal = goal
            initializedTree = tree
            return TaskState(id = "plan", title = goal, status = "active", steps = tree)
        }

        override suspend fun updatePlan(sessionId: String, operations: List<PlanPatchOp>): TaskState {
            updated = operations
            return TaskState(id = "plan", status = "active")
        }

        override suspend fun getPlan(sessionId: String): TaskState? = null
        override suspend fun dropPlan(sessionId: String, reason: String) = Unit
        override fun deriveParentStatus(children: List<TaskStep>): String = "pending"
        override fun countLeafProgress(steps: List<TaskStep>): Pair<Int, Int> = 0 to 0
    }
}
