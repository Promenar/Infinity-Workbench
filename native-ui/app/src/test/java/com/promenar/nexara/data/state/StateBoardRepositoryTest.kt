package com.promenar.nexara.data.state

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.*
import com.promenar.nexara.data.repository.StateBoardRepository
import com.promenar.nexara.data.session.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class StateBoardRepositoryTest {
    private lateinit var db: NexaraDatabase
    private lateinit var repo: StateBoardRepository
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NexaraDatabase::class.java).allowMainThreadQueries().build()
        repo = StateBoardRepository(db)
        db.agentDao().insert(AgentEntity("agent", "助手", createdAt = 1))
        db.sessionDao().insert(SessionEntity("session", "agent", "会话", createdAt = 1, updatedAt = 1))
        message("u1", "user", 1)
        message("a1", "assistant", 2, "u1")
    }
    @After fun teardown() = db.close()
    private suspend fun message(id: String, role: String, time: Long, parent: String? = null) = db.messageDao().insert(
        MessageEntity(id, "session", role, "正文", parentMessageId = parent, createdAt = time),
    )
    private suspend fun define(owner: String = "a1") = repo.define("session", owner, "冒险", listOf(StateField("hp", "生命值", "number", JsonPrimitive(80))))
    private suspend fun change(owner: String, value: Int, excluded: Set<String> = emptySet()) = repo.update("session", owner, listOf(StateOperation("hp", "set", JsonPrimitive(value))), excluded)
    private suspend fun hp(session: String = "session", excluded: Set<String> = emptySet()) = repo.get(session, excluded)?.board?.fields?.single()?.value

    @Test fun `删除和清空消息自动回滚用户与AI版本且不影响其它会话`() = runBlocking {
        define()
        message("u2", "user", 3)
        message("a2", "assistant", 4, "u2")
        change("a2", 65)
        val revision = repo.get("session")!!.revision
        repo.edit("session", revision, listOf(StateOperation("hp", "set", JsonPrimitive(70))))
        assertThat(repo.get("session")!!.board!!.manualKeys).containsExactly("hp")
        db.messageDao().deleteByIdsInSessionWithContextSummary("session", listOf("u2"))
        assertThat(hp()).isEqualTo(JsonPrimitive(80))
        db.messageDao().deleteBySessionIdWithContextSummary("session")
        assertThat(repo.get("session")).isNull()
    }
    @Test fun `重新生成从保留消息快照开始失败恢复旧版本成功撤销旧轮`() = runBlocking {
        define()
        message("a2", "assistant", 3, "u1")
        change("a2", 65)
        message("replacement", "assistant", 4, "u1")
        val excluded = setOf("a2")
        assertThat(hp(excluded = excluded)).isEqualTo(JsonPrimitive(80))
        repo.update("session", "replacement", listOf(StateOperation("hp", "increment", JsonPrimitive(-10))), excluded)
        assertThat(hp()).isEqualTo(JsonPrimitive(70))
        db.messageDao().deleteByIdWithContextSummary("replacement")
        assertThat(hp()).isEqualTo(JsonPrimitive(65))
        message("success", "assistant", 5, "u1")
        change("success", 72, excluded)
        db.messageDao().deleteByIdWithContextSummary("a2")
        assertThat(hp()).isEqualTo(JsonPrimitive(72))
    }
    @Test fun `分支复制截点及之前版本并映射消息身份`() = runBlocking {
        define()
        message("u2", "user", 3)
        message("a2", "assistant", 4, "u2")
        change("a2", 65)
        val result = BranchSessionUseCase(db).branch("session", "a1") as BranchSessionResult.Success
        assertThat(hp(result.sessionId)).isEqualTo(JsonPrimitive(80))
        val copied = db.stateBoardDao().versions(result.sessionId)
        assertThat(copied).hasSize(1)
        assertThat(copied.single().anchorMessageId).isNotEqualTo("a1")
        assertThat(db.messageDao().getById(copied.single().anchorMessageId)!!.sessionId).isEqualTo(result.sessionId)
        db.sessionDao().deleteById(result.sessionId)
        assertThat(db.stateBoardDao().versions(result.sessionId)).isEmpty()
        assertThat(hp()).isEqualTo(JsonPrimitive(65))
    }
    @Test fun `同消息压缩保持各消息回滚点且批量错误不写版本`() = runBlocking {
        define()
        message("a2", "assistant", 3, "u1")
        repeat(25) { change("a2", it) }
        assertThat(db.stateBoardDao().versions("session").count { it.anchorMessageId == "a2" }).isAtMost(StateBoardRepository.MAX_VERSIONS_PER_MESSAGE)
        val previous = repo.get("session")!!
        try {
            repo.update("session", "a2", listOf(StateOperation("hp", "set", JsonPrimitive(1)), StateOperation("missing", "clear")))
            Assert.fail("必须拒绝不存在的字段")
        } catch (_: IllegalArgumentException) { }
        assertThat(repo.get("session")).isEqualTo(previous)
        db.messageDao().deleteByIdWithContextSummary("a2")
        assertThat(hp()).isEqualTo(JsonPrimitive(80))
    }
    @Test fun `移除可回滚且手动编辑过期版本被拒绝`() = runBlocking {
        define()
        val first = repo.get("session")!!.revision
        change("a1", 70)
        try { repo.edit("session", first, listOf(StateOperation("hp", "clear"))); Assert.fail("必须拒绝过期编辑") } catch (_: IllegalArgumentException) { }
        message("a2", "assistant", 3, "u1")
        repo.remove("session", "a2")
        assertThat(repo.get("session")!!.board).isNull()
        db.messageDao().deleteByIdWithContextSummary("a2")
        assertThat(hp()).isEqualTo(JsonPrimitive(70))
    }
    @Test fun `会话事务删除级联移除快照且导出的删除版本可撤销恢复`() = runBlocking {
        define()
        val rows = db.stateBoardDao().versions("session")
        db.messageDao().deleteByIdWithContextSummary("a1")
        message("a1", "assistant", 2, "u1")
        repo.restoreVersions(rows)
        assertThat(hp()).isEqualTo(JsonPrimitive(80))
        assertThat(RoomSessionDeletionTransaction(db).deleteWithoutWorkspace("session")).isTrue()
        assertThat(db.stateBoardDao().versions("session")).isEmpty()
    }

    @Test fun `应用级重新生成隔离跨观察者存续并保护手动编辑基线`() = runBlocking {
        define()
        message("old", "assistant", 3, "u1")
        change("old", 10)
        message("replacement", "assistant", 4, "u1")
        repo.beginGeneration("session", "replacement", setOf("old"))
        // 新观察者不依赖任何 ViewModel 本地替换集合。
        val reopened = repo.observe("session").first()!!
        assertThat(reopened.board!!.fields.single().value).isEqualTo(JsonPrimitive(80))
        repo.edit("session", reopened.revision, listOf(StateOperation("hp", "set", JsonPrimitive(90))))
        assertThat(hp()).isEqualTo(JsonPrimitive(90))
        repo.beginGeneration("session", "new-owner", setOf("old"))
        repo.endGeneration("session", "replacement")
        assertThat(repo.get("session")!!.board!!.fields.single().value).isEqualTo(JsonPrimitive(90))
        db.messageDao().deleteByIdWithContextSummary("replacement")
        repo.endGeneration("session", "new-owner")
        assertThat(hp()).isEqualTo(JsonPrimitive(10))
    }
    @Test fun `请求期间用户改值不会被旧模型值覆盖且其它字段更新保留通知`() = runBlocking {
        repo.define("session", "a1", "面板", listOf(
            StateField("hp", "生命值", "number", JsonPrimitive(80)),
            StateField("bag", "背包", "list", JsonArray(emptyList())),
        ))
        val seen = repo.get("session")!!.revision
        repo.edit("session", seen, listOf(StateOperation("hp", "set", JsonPrimitive(90))))
        repo.update("session", "a1", listOf(StateOperation("bag", "append", JsonArray(listOf(JsonPrimitive("火把"))))), seenRevision = seen)
        assertThat(repo.get("session")!!.board!!.manualKeys).containsExactly("hp")
        try {
            repo.update("session", "a1", listOf(StateOperation("hp", "set", JsonPrimitive(65))), seenRevision = seen)
            Assert.fail("必须保护请求之后的用户编辑")
        } catch (_: IllegalArgumentException) { }
        assertThat(repo.get("session")!!.board!!.fields.first().value).isEqualTo(JsonPrimitive(90))
        val fresh = repo.get("session")!!.revision
        repo.update("session", "a1", listOf(StateOperation("hp", "increment", JsonPrimitive(-5))), seenRevision = fresh)
        assertThat(repo.get("session")!!.board!!.fields.first().value).isEqualTo(JsonPrimitive(85))
        assertThat(repo.get("session")!!.board!!.manualKeys).isEmpty()
    }
    @Test fun `删除捕获和消息删除同事务失败全部回滚撤销保留最终快照`() = runBlocking {
        define()
        repo.edit("session", repo.get("session")!!.revision, listOf(StateOperation("hp", "set", JsonPrimitive(90))))
        try {
            repo.captureDeletion("session", setOf("a1")) {
                db.messageDao().deleteByIdWithContextSummary("a1")
                error("模拟删除事务失败")
            }
            Assert.fail("必须回滚失败事务")
        } catch (_: IllegalStateException) { }
        assertThat(hp()).isEqualTo(JsonPrimitive(90))
        assertThat(db.messageDao().getById("a1")).isNotNull()
        val captured = repo.captureDeletion("session", setOf("a1")) { db.messageDao().deleteByIdWithContextSummary("a1") }
        assertThat(repo.get("session")).isNull()
        message("a1", "assistant", 2, "u1")
        repo.restoreVersions(captured)
        assertThat(hp()).isEqualTo(JsonPrimitive(90))
    }

    @Test fun `请求后用户移除面板时旧模型不能直接重建`() = runBlocking {
        val initial = define()
        repo.removeByUser("session", initial.revision)
        try {
            repo.define("session", "a1", "面板", listOf(StateField("hp", "生命值", "number", JsonPrimitive(1))), seenRevision = initial.revision)
            Assert.fail("不能覆盖用户刚做的移除")
        } catch (_: IllegalArgumentException) { }
        assertThat(repo.get("session")!!.board).isNull()
    }
}
