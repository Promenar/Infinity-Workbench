package com.promenar.nexara.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.dao.TaskNodeDao
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.domain.repository.PlanPatchOp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TaskRepositoryTest {
    private lateinit var db: NexaraDatabase
    private lateinit var dao: TaskNodeDao
    private lateinit var sessionDao: SessionDao
    private lateinit var repo: TaskRepository

    private val sessionId = "test-session"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.taskNodeDao()
        sessionDao = db.sessionDao()
        repo = TaskRepository(dao)

        runBlocking {
            sessionDao.insert(
                SessionEntity(
                    id = sessionId,
                    agentId = "agent1",
                    createdAt = 1000L,
                    updatedAt = 1000L,
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun buildTree(
        vararg steps: Pair<String, String>
    ): List<TaskStep> {
        return steps.map { (id, title) ->
            TaskStep(id = id, title = title, children = emptyList())
        }
    }

    private fun buildNestedTree(): List<TaskStep> {
        return listOf(
            TaskStep(
                id = "s1",
                title = "收集资料",
                children = listOf(
                    TaskStep(id = "s1a", title = "阅读文档", children = emptyList()),
                    TaskStep(id = "s1b", title = "整理清单", children = emptyList()),
                )
            ),
            TaskStep(
                id = "s2",
                title = "撰写报告",
                children = listOf(
                    TaskStep(id = "s2a", title = "编写正文", children = emptyList()),
                )
            ),
        )
    }

    @Test
    fun `initializePlan creates tree and sets first leaf to doing`() = runBlocking {
        val tree = buildNestedTree()

        val result = repo.initializePlan(sessionId, "编写报告", tree)

        assertThat(result.status).isEqualTo("active")
        assertThat(result.title).isEqualTo("编写报告")
        assertThat(result.steps).hasSize(2)

        val firstLeaf = result.steps[0].children[0]
        assertThat(firstLeaf.status).isEqualTo("doing")
        assertThat(result.currentFocusStepId).isEqualTo("s1a")
    }

    @Test
    fun `initializePlan returns conflict when active plan exists`() = runBlocking {
        val tree = buildNestedTree()
        repo.initializePlan(sessionId, "第一个任务", tree)

        val result = repo.initializePlan(sessionId, "第二个任务", tree)

        assertThat(result.status).isEqualTo("conflict")
        assertThat(result.title).isEqualTo("收集资料")
    }

    @Test
    fun `updatePlan set_status changes leaf node status`() = runBlocking {
        val tree = buildNestedTree()
        repo.initializePlan(sessionId, "测试", tree)

        val result = repo.updatePlan(sessionId, listOf(
            PlanPatchOp(
                action = "set_status",
                stepId = "s1a",
                payload = mapOf("status" to "done")
            )
        ))

        val step = result.steps[0].children[0]
        assertThat(step.status).isEqualTo("done")
    }

    @Test
    fun `updatePlan set_status doing resets previous doing to todo`() = runBlocking {
        val tree = buildNestedTree()
        repo.initializePlan(sessionId, "测试", tree)

        val result = repo.updatePlan(sessionId, listOf(
            PlanPatchOp(
                action = "set_status",
                stepId = "s1b",
                payload = mapOf("status" to "doing")
            )
        ))

        val prevDoing = result.steps[0].children[0]
        assertThat(prevDoing.status).isEqualTo("todo")

        val newDoing = result.steps[0].children[1]
        assertThat(newDoing.status).isEqualTo("doing")
        assertThat(result.currentFocusStepId).isEqualTo("s1b")
    }

    @Test
    fun `updatePlan set_status on parent node throws ParentStatusDerivedException`() = runBlocking {
        val tree = buildNestedTree()
        repo.initializePlan(sessionId, "测试", tree)

        var caught = false
        try {
            repo.updatePlan(sessionId, listOf(
                PlanPatchOp(
                    action = "set_status",
                    stepId = "s1",
                    payload = mapOf("status" to "done")
                )
            ))
        } catch (e: TaskRepository.ParentStatusDerivedException) {
            caught = true
            assertThat(e.stepId).isEqualTo("s1")
            assertThat(e.childCount).isEqualTo(2)
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun `getPlan returns correct leafProgress and derived parent status`() = runBlocking {
        val tree = buildNestedTree()
        repo.initializePlan(sessionId, "测试", tree)

        repo.updatePlan(sessionId, listOf(
            PlanPatchOp(
                action = "set_status",
                stepId = "s1a",
                payload = mapOf("status" to "done")
            ),
            PlanPatchOp(
                action = "set_status",
                stepId = "s1b",
                payload = mapOf("status" to "done")
            ),
            PlanPatchOp(
                action = "set_status",
                stepId = "s2a",
                payload = mapOf("status" to "doing")
            ),
        ))

        val plan = repo.getPlan(sessionId)!!

        val (done, total) = repo.countLeafProgress(plan.steps)
        assertThat(done).isEqualTo(2)
        assertThat(total).isEqualTo(3)

        val parent1 = plan.steps[0]
        assertThat(parent1.status).isEqualTo("done")

        val parent2 = plan.steps[1]
        assertThat(parent2.status).isEqualTo("doing")
        assertThat(plan.currentFocusStepId).isEqualTo("s2a")
    }

    @Test
    fun `getPlan returns null when no plan exists`() = runBlocking {
        val result = repo.getPlan(sessionId)
        assertThat(result).isNull()
    }

    @Test
    fun `dropPlan marks all nodes as dropped`() = runBlocking {
        val tree = buildNestedTree()
        repo.initializePlan(sessionId, "测试", tree)

        repo.dropPlan(sessionId, "用户取消")

        val plan = repo.getPlan(sessionId)
        assertThat(plan).isNull()

        val allNodes = dao.getAllActiveBySession(sessionId)
        assertThat(allNodes).isEmpty()
    }

    @Test
    fun `deriveParentStatus all done`() {
        val children = listOf(
            TaskStep(id = "a", status = "done", children = emptyList()),
            TaskStep(id = "b", status = "done", children = emptyList()),
        )
        assertThat(repo.deriveParentStatus(children)).isEqualTo("done")
    }

    @Test
    fun `deriveParentStatus some doing`() {
        val children = listOf(
            TaskStep(id = "a", status = "done", children = emptyList()),
            TaskStep(id = "b", status = "doing", children = emptyList()),
        )
        assertThat(repo.deriveParentStatus(children)).isEqualTo("doing")
    }

    @Test
    fun `deriveParentStatus all todo`() {
        val children = listOf(
            TaskStep(id = "a", status = "todo", children = emptyList()),
            TaskStep(id = "b", status = "todo", children = emptyList()),
        )
        assertThat(repo.deriveParentStatus(children)).isEqualTo("todo")
    }

    @Test
    fun `deriveParentStatus some dropped`() {
        val children = listOf(
            TaskStep(id = "a", status = "done", children = emptyList()),
            TaskStep(id = "b", status = "dropped", children = emptyList()),
        )
        assertThat(repo.deriveParentStatus(children)).isEqualTo("partial_dropped")
    }

    @Test
    fun `deriveParentStatus empty children returns todo`() {
        assertThat(repo.deriveParentStatus(emptyList())).isEqualTo("todo")
    }

    @Test
    fun `countLeafProgress counts only leaf nodes`() {
        val tree = listOf(
            TaskStep(
                id = "s1",
                status = "done",
                children = listOf(
                    TaskStep(id = "s1a", status = "done", children = emptyList()),
                    TaskStep(id = "s1b", status = "doing", children = emptyList()),
                )
            ),
            TaskStep(id = "s2", status = "todo", children = emptyList()),
        )

        val (done, total) = repo.countLeafProgress(tree)
        assertThat(done).isEqualTo(1)
        assertThat(total).isEqualTo(3)
    }

    @Test
    fun `updatePlan add_step creates new node`() = runBlocking {
        val tree = buildNestedTree()
        repo.initializePlan(sessionId, "测试", tree)

        val result = repo.updatePlan(sessionId, listOf(
            PlanPatchOp(
                action = "add_step",
                parentId = "s2",
                stepId = "s2b",
                payload = mapOf("title" to "添加图表", "sortOrder" to "1")
            )
        ))

        val s2Children = result.steps[1].children
        assertThat(s2Children).hasSize(2)
        assertThat(s2Children.any { it.title == "添加图表" }).isTrue()
    }

    @Test
    fun `updatePlan update_title changes node title`() = runBlocking {
        val tree = buildNestedTree()
        repo.initializePlan(sessionId, "测试", tree)

        val result = repo.updatePlan(sessionId, listOf(
            PlanPatchOp(
                action = "update_title",
                stepId = "s1a",
                payload = mapOf("title" to "全新标题")
            )
        ))

        assertThat(result.steps[0].children[0].title).isEqualTo("全新标题")
    }

    @Test
    fun `updatePlan set_note adds note to node`() = runBlocking {
        val tree = buildNestedTree()
        repo.initializePlan(sessionId, "测试", tree)

        val result = repo.updatePlan(sessionId, listOf(
            PlanPatchOp(
                action = "set_note",
                stepId = "s1a",
                payload = mapOf("note" to "已完成阅读")
            )
        ))

        assertThat(result.steps[0].children[0].note).isEqualTo("已完成阅读")
    }

    @Test
    fun `full lifecycle - initialize update complete drop`() = runBlocking {
        val tree = buildNestedTree()

        val init = repo.initializePlan(sessionId, "完整生命周期", tree)
        assertThat(init.status).isEqualTo("active")

        repo.updatePlan(sessionId, listOf(
            PlanPatchOp(action = "set_status", stepId = "s1a", payload = mapOf("status" to "done")),
            PlanPatchOp(action = "set_status", stepId = "s1b", payload = mapOf("status" to "done")),
            PlanPatchOp(action = "set_status", stepId = "s2a", payload = mapOf("status" to "doing")),
        ))

        val mid = repo.getPlan(sessionId)!!
        assertThat(mid.steps[0].status).isEqualTo("done")

        repo.updatePlan(sessionId, listOf(
            PlanPatchOp(action = "set_status", stepId = "s2a", payload = mapOf("status" to "done")),
        ))

        val done = repo.getPlan(sessionId)!!
        assertThat(done.status).isEqualTo("done")
        val (doneCount, totalCount) = repo.countLeafProgress(done.steps)
        assertThat(doneCount).isEqualTo(totalCount)
    }
}
