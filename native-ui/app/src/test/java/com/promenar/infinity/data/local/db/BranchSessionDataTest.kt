package com.promenar.infinity.data.local.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.model.toDomain
import com.promenar.infinity.data.repository.SessionRepository
import com.promenar.infinity.data.session.RoomSessionDeletionTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class BranchSessionDataTest {
    private lateinit var database: InfinityDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `旧快照不能撤销分支绑定与自动处理计数`() = runBlocking {
        val dao = database.sessionDao()
        val old = SessionEntity("branch", "agent", createdAt = 1, updatedAt = 1)
        dao.insert(old)
        val current = old.copy(parentSessionId = "parent", branchPlanJson = "{}",
            worktreeDirectory = "/workspace/feature", worktreeBranch = "feature", worktreeBase = "base",
            parentSessionDeleted = true, sessionAutoWakeCount = 8, sessionAutoProcessingPaused = true)
        dao.updateRow(current)

        dao.update(old.copy(title = "新标题"))

        assertThat(dao.getById("branch")).isEqualTo(current.copy(title = "新标题"))
        dao.updateRow(current.copy(parentSessionId = null, sessionAutoWakeCount = 0, sessionAutoProcessingPaused = false))
        dao.update(current.copy(title = "用户输入后"))
        assertThat(dao.getById("branch")!!.sessionAutoWakeCount).isEqualTo(0)
        assertThat(dao.getById("branch")!!.sessionAutoProcessingPaused).isFalse()
        assertThat(dao.getById("branch")!!.parentSessionId).isNull()
    }

    @Test
    fun `仓库领域观察提供首页分组与worktree字段`() = runBlocking {
        val child = seedFamily()
        val repository = SessionRepository(database.sessionDao(), database.messageDao())
        val grouped = repository.observeByAgent("agent").first().first { it.id == child.id }
        assertThat(grouped.parentSessionId).isEqualTo("parent")
        assertThat(grouped.branchPlanJson).isEqualTo(child.branchPlanJson)
        assertThat(grouped.worktreeDirectory).isEqualTo(child.worktreeDirectory)
        assertThat(grouped.worktreeBranch).isEqualTo(child.worktreeBranch)
        assertThat(grouped.worktreeBase).isEqualTo(child.worktreeBase)
        assertThat(grouped.parentSessionDeleted).isFalse()
        assertThat(repository.observeById(child.id).first()!!.parentSessionId).isEqualTo("parent")
    }

    @Test
    fun `无工作区删除父会话保留子会话消息和绑定`() = runBlocking {
        val child = seedFamily()
        assertThat(RoomSessionDeletionTransaction(database).deleteWithoutWorkspace("parent")).isTrue()
        assertDetachedChild(child)
    }

    @Test
    fun `仓库删除父会话保留子会话且旧快照不能复活父引用`() = runBlocking {
        val child = seedFamily()
        val repository = SessionRepository(database.sessionDao(), database.messageDao())
        repository.delete("parent")
        repository.update(child.toDomain().copy(title = "保留分支"))
        assertDetachedChild(child.copy(title = "保留分支"))
    }

    @Test
    fun `按实体删除父会话也在同一事务内解除子引用`() = runBlocking {
        val child = seedFamily()
        database.sessionDao().delete(database.sessionDao().getById("parent")!!)
        assertDetachedChild(child)
    }

    @Test
    fun `父会话删除失败时子引用与删除标记一起回滚`() = runBlocking {
        val child = seedFamily()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_parent_delete BEFORE DELETE ON sessions WHEN OLD.id = 'parent' BEGIN SELECT RAISE(ABORT, 'test-delete-failed'); END")

        val failure = runCatching { database.sessionDao().deleteById("parent") }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(database.sessionDao().getById("parent")).isNotNull()
        assertThat(database.sessionDao().getById("child")).isEqualTo(child)
        assertThat(database.messageDao().getById("child-message")).isNotNull()
    }

    private suspend fun seedFamily(): SessionEntity {
        database.sessionDao().insert(SessionEntity("parent", "agent", createdAt = 1, updatedAt = 1))
        val child = SessionEntity("child", "agent", createdAt = 1, updatedAt = 2,
            parentSessionId = "parent", branchPlanJson = "{}", worktreeDirectory = "/workspace/child",
            worktreeBranch = "feature", worktreeBase = "base", sessionAutoWakeCount = 8,
            sessionAutoProcessingPaused = true)
        database.sessionDao().insert(child)
        database.messageDao().insert(MessageEntity("child-message", "child", "assistant", "分支记录", createdAt = 2))
        return child
    }

    private suspend fun assertDetachedChild(original: SessionEntity) {
        assertThat(database.sessionDao().getById("parent")).isNull()
        val child = database.sessionDao().getById(original.id)!!
        assertThat(child.parentSessionId).isNull()
        assertThat(child.parentSessionDeleted).isTrue()
        assertThat(child.title).isEqualTo(original.title)
        assertThat(child.branchPlanJson).isEqualTo(original.branchPlanJson)
        assertThat(child.worktreeDirectory).isEqualTo(original.worktreeDirectory)
        assertThat(child.worktreeBranch).isEqualTo(original.worktreeBranch)
        assertThat(child.worktreeBase).isEqualTo(original.worktreeBase)
        assertThat(child.sessionAutoWakeCount).isEqualTo(original.sessionAutoWakeCount)
        assertThat(child.sessionAutoProcessingPaused).isEqualTo(original.sessionAutoProcessingPaused)
        assertThat(database.messageDao().getById("child-message")!!.content).isEqualTo("分支记录")
    }
}
