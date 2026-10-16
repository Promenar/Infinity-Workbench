package com.promenar.nexara.ui.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.data.repository.TaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TaskPlanActionsTest {
    private lateinit var db: NexaraDatabase
    private lateinit var repository: TaskRepository
    private lateinit var actions: TaskPlanActions
    private val sessionId = "session"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(db.taskNodeDao(), db)
        actions = TaskPlanActions(repository)
        runBlocking {
            db.sessionDao().insert(SessionEntity(id = sessionId, agentId = "agent", createdAt = 1L, updatedAt = 1L))
            repository.initializePlan(
                sessionId,
                "发布",
                listOf(
                    TaskStep(id = "a", title = "检查", sortOrder = 0),
                    TaskStep(id = "b", title = "构建", sortOrder = 1),
                    TaskStep(id = "c", title = "放弃项", sortOrder = 2),
                ),
            )
            repository.updatePlan(
                sessionId,
                listOf(
                    com.promenar.nexara.domain.repository.PlanPatchOp("set_status", "c", payload = mapOf("status" to "dropped")),
                ),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun statuses(): Map<String, String> =
        requireNotNull(repository.getPlan(sessionId)).steps.associate { it.id to it.status }

    @Test
    fun `标记完成只改未完成步骤并可原样撤销`() = runBlocking {
        val before = statuses()
        assertThat(before).containsExactly("a", "doing", "b", "todo")

        val previous = actions.completeRemaining(sessionId)

        assertThat(previous).containsExactly("a", "doing", "b", "todo")
        assertThat(statuses()).containsExactly("a", "done", "b", "done")

        actions.restore(sessionId, requireNotNull(previous))

        assertThat(statuses()).isEqualTo(before)
    }

    @Test
    fun `已全部完成时标记完成无操作，清除后面板消失`() = runBlocking {
        actions.completeRemaining(sessionId)

        assertThat(actions.completeRemaining(sessionId)).isNull()
        assertThat(actions.clearCompleted(sessionId)).isTrue()
        assertThat(repository.getPlan(sessionId)).isNull()
    }

    @Test
    fun `仍有未完成步骤时不允许清除`() = runBlocking {
        assertThat(actions.clearCompleted(sessionId)).isFalse()
        assertThat(repository.getPlan(sessionId)).isNotNull()
    }
}
