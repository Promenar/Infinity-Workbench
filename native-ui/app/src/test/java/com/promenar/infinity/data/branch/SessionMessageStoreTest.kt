package com.promenar.infinity.data.branch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.model.*
import com.promenar.infinity.domain.branch.*
import com.promenar.infinity.domain.trigger.*
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.MessageManager
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionMessageStoreTest {
    private lateinit var db: InfinityDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var store: ChatStore
    private lateinit var messages: MessageManager
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        store = ChatStore()
        messages = mockk(relaxed = true)
        listOf("parent", "branch").forEach {
            val row = SessionEntity(it, "a", parentSessionId = if (it == "branch") "parent" else null, createdAt = 1, updatedAt = 1)
            db.sessionDao().insert(row); store.upsertSession(row.toDomain())
        }
    }
    @After fun cleanup() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    private fun mailbox(dispatcher: SystemTurnDispatcher = object : SystemTurnDispatcher {
        override suspend fun request(request: SystemTurnRequest) = SystemTurnOutcome.Completed
    }) = SessionMessageStore(db, scope, { dispatcher }, store, messages, { "已暂停自动处理，等你回来继续" })

    @Test fun `八次无用户输入唤醒后只记录并持久暂停用户输入清零`() = runBlocking {
        val box = mailbox()
        repeat(8) { assertThat(box.admitWake("parent", "mail_$it", SystemTurnKind.SESSION_MESSAGE)).isTrue() }
        assertThat(box.admitWake("parent", "mail_9", SystemTurnKind.SESSION_MESSAGE)).isFalse()
        assertThat(db.sessionDao().getById("parent")!!.sessionAutoWakeCount).isEqualTo(8)
        assertThat(db.sessionDao().getById("parent")!!.sessionAutoProcessingPaused).isTrue()
        assertThat(db.messageDao().getBySession("parent").any { it.name == SessionMessageStore.PAUSED_MESSAGE_NAME }).isTrue()
        assertThat(mailbox().admitWake("parent", "mail_10", SystemTurnKind.SESSION_MESSAGE)).isFalse()
        box.resetForUserInput("parent")
        assertThat(box.admitWake("parent", "mail_11", SystemTurnKind.SESSION_MESSAGE)).isTrue()
        assertThat(db.sessionDao().getById("parent")!!.sessionAutoWakeCount).isEqualTo(1)
    }
    @Test fun `同一已持久消息恢复不重复计数子代理通知不计数`() = runBlocking {
        val box = mailbox()
        assertThat(box.admitWake("parent", "same", SystemTurnKind.SESSION_MESSAGE)).isTrue()
        assertThat(mailbox().admitWake("parent", "same", SystemTurnKind.SESSION_MESSAGE)).isTrue()
        assertThat(box.admitWake("parent", "subagent", SystemTurnKind.SUBAGENT_DONE)).isTrue()
        assertThat(db.sessionDao().getById("parent")!!.sessionAutoWakeCount).isEqualTo(1)
    }
    @Test fun `发送记录先落盘且请求有稳定送达ID和接收条目`() = runBlocking {
        val arrived = CompletableDeferred<SystemTurnRequest>()
        val box = mailbox(object : SystemTurnDispatcher {
            override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome {
                // 发送副本写在工具调用与结果之间，必须是 SYSTEM 角色，不能成为用户轮次边界。
                assertThat(db.messageDao().getBySession("branch").single { it.name == SessionMessageStore.SENT_MESSAGE_NAME }.role).isEqualTo("system")
                arrived.complete(request)
                return SystemTurnOutcome.Completed
            }
        })
        box.send("branch", "parent", SessionMessageEntry(SessionMessageEntry.Direction.SENT, "parent",
            SessionMessageEntry.PeerRelation.PARENT, "主会话", "阶段结论", 1), false)
        val request = withTimeout(5000) { arrived.await() }
        assertThat(request.kind).isEqualTo(SystemTurnKind.SESSION_MESSAGE)
        assertThat(request.sourceId).isEqualTo("branch")
        assertThat(request.deliveryIds).hasSize(1)
        assertThat(request.body).contains("\"direction\":\"received\"")
        assertThat(request.body).contains("\"peerRelation\":\"branch\"")
    }
    @Test fun `发送后进程结束重启补送沿用同一ID`() = runBlocking {
        val first = CompletableDeferred<SystemTurnRequest>()
        val box = mailbox(object : SystemTurnDispatcher {
            override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome { first.complete(request); awaitCancellation() }
        })
        box.send("branch", "parent", SessionMessageEntry(SessionMessageEntry.Direction.SENT, "parent",
            SessionMessageEntry.PeerRelation.PARENT, "主会话", "结论", 1), true)
        val original = withTimeout(5000) { first.await() }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val replayed = CompletableDeferred<SystemTurnRequest>()
        mailbox(object : SystemTurnDispatcher {
            override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome { replayed.complete(request); return SystemTurnOutcome.Completed }
        }).recover()
        assertThat(withTimeout(5000) { replayed.await() }.deliveryIds).isEqualTo(original.deliveryIds)
    }
    @Test fun `发送方删除后延迟回执不插入孤立消息也不逃逸异常`() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> errors += error })
        mailbox(object : SystemTurnDispatcher {
            override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome {
                waiting.complete(Unit); release.await(); return SystemTurnOutcome.Completed
            }
        }).send("branch", "parent", SessionMessageEntry(SessionMessageEntry.Direction.SENT, "parent",
            SessionMessageEntry.PeerRelation.PARENT, "主会话", "结论", 1), false)
        withTimeout(5_000) { waiting.await() }
        db.sessionDao().deleteById("branch")
        release.complete(Unit)
        withTimeout(5_000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
        assertThat(errors).isEmpty()
        assertThat(db.messageDao().getBySession("branch")).isEmpty()
    }
    @Test fun `历史发送记录裁剪后新消息序号仍在未送达消息之后`() = runBlocking {
        val entry = SessionMessageEntry(SessionMessageEntry.Direction.SENT, "parent", SessionMessageEntry.PeerRelation.PARENT, "主会话", "结论", 1)
        db.messageDao().insert(Message("old-mail", MessageRole.USER,
            kotlinx.serialization.json.Json.encodeToString(SessionMessageOutbox("parent", entry, sequence = 20)),
            name = SessionMessageStore.SENT_MESSAGE_NAME).toEntity("branch"))
        val delivered = CompletableDeferred<SystemTurnRequest>()
        mailbox(object : SystemTurnDispatcher {
            override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome { delivered.complete(request); return SystemTurnOutcome.Completed }
        }).send("branch", "parent", entry, false)
        assertThat(withTimeout(5_000) { delivered.await() }.sequence).isEqualTo(21)
    }
}
