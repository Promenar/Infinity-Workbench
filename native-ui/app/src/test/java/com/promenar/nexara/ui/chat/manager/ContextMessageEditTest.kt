package com.promenar.nexara.ui.chat.manager

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.generation.DefaultChatGenerationContentStrategy
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.ContextSummaryEntity
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.repository.MessagePersistenceException
import com.promenar.nexara.data.repository.MessageRepository
import com.promenar.nexara.data.repository.SessionRepository
import com.promenar.nexara.ui.chat.ChatStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ContextMessageEditTest {
    private val databases = mutableListOf<NexaraDatabase>()

    @After
    fun closeDatabases() {
        databases.forEach(NexaraDatabase::close)
    }

    @Test
    fun `编辑已摘要正文同事务清除旧摘要并恢复全部原文且Store一次发布`() = runTest {
        val fixture = fixture()
        val observed = mutableListOf<Session>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.store.state.collect { state -> state.sessions.firstOrNull { it.id == SESSION }?.let(observed::add) }
        }

        fixture.manager.editMessageContent(SESSION, OLD_USER, "事实B")

        val persisted = checkNotNull(fixture.sessions.getById(SESSION))
        val visible = fixture.current()
        assertThat(persisted.summary).isNull()
        assertThat(visible.summary).isNull()
        assertThat(persisted.messages.all { !it.isArchived }).isTrue()
        assertThat(visible.messages.all { !it.isArchived }).isTrue()
        assertThat(fixture.database.sessionDao().getCurrentContextSummary(SESSION)).isNull()
        assertThat(fixture.database.messageDao().getById(OLD_USER)?.content).isEqualTo("事实B")
        assertThat(fixture.protocolText(visible)).contains("事实B")
        assertThat(fixture.protocolText(visible)).doesNotContain("事实A")
        assertThat(fixture.protocolText(persisted)).isEqualTo(fixture.protocolText(visible))
        assertThat(observed.filter { it.messages.first { message -> message.id == OLD_USER }.content == "事实B" })
            .hasSize(1)
        assertThat(observed.any { snapshot ->
            snapshot.messages.first { it.id == OLD_USER }.content == "事实B" &&
                (snapshot.summary != null || snapshot.messages.any { it.isArchived })
        }).isFalse()
    }

    @Test
    fun `编辑最近未摘要消息保留有效摘要及历史归档状态`() = runTest {
        val fixture = fixture()

        fixture.manager.editMessageContent(SESSION, CURRENT, "当前B")

        val persisted = checkNotNull(fixture.sessions.getById(SESSION))
        val visible = fixture.current()
        assertThat(persisted.summary).isEqualTo(SUMMARY)
        assertThat(visible.summary).isEqualTo(SUMMARY)
        assertThat(persisted.messages.filter { it.id != CURRENT }.all { it.isArchived }).isTrue()
        assertThat(visible.messages.filter { it.id != CURRENT }.all { it.isArchived }).isTrue()
        assertThat(persisted.messages.single { it.id == CURRENT }.content).isEqualTo("当前B")
        assertThat(persisted.messages.single { it.id == CURRENT }.isArchived).isFalse()
        val protocol = fixture.protocolText(visible)
        assertThat(protocol).contains(SUMMARY)
        assertThat(protocol).contains("当前B")
        assertThat(protocol).doesNotContain("当前A")
        assertThat(fixture.database.sessionDao().getCurrentContextSummary(SESSION)?.summaryContent).isEqualTo(SUMMARY)
    }

    @Test
    fun `跨会话和不存在消息的编辑明确失败且所有会话保持原样`() = runTest {
        val fixture = fixture()
        val other = Session("other-session", "agent")
        fixture.sessions.create(other)
        fixture.messages.insert(Message("other-message", MessageRole.USER, "其它会话正文", createdAt = 4), other.id)
        fixture.store.upsertSession(checkNotNull(fixture.sessions.getById(other.id)))
        val initialStore = fixture.store.get()
        val initialSession = fixture.sessions.getById(SESSION)
        val initialOther = fixture.sessions.getById(other.id)

        for (id in listOf("other-message", "missing-message")) {
            val failure = runCatching { fixture.manager.editMessageContent(SESSION, id, "不应写入") }.exceptionOrNull()

            assertThat(failure).isInstanceOf(MessagePersistenceException::class.java)
            assertThat(fixture.store.get()).isEqualTo(initialStore)
            assertThat(fixture.sessions.getById(SESSION)).isEqualTo(initialSession)
            assertThat(fixture.sessions.getById(other.id)).isEqualTo(initialOther)
        }
    }

    @Test
    fun `编辑持久化失败或取消时不会向Store发布成功正文或清除摘要`() = runTest {
        for (failure in listOf(IOException("编辑失败"), CancellationException("编辑取消"))) {
            val fixture = fixture(beforeEdit = { throw failure })
            val initialStore = fixture.store.get()
            val initialDatabase = fixture.sessions.getById(SESSION)

            val observed = runCatching { fixture.manager.editMessageContent(SESSION, OLD_USER, "事实B") }.exceptionOrNull()

            assertThat(observed).isSameInstanceAs(failure)
            assertThat(fixture.store.get()).isEqualTo(initialStore)
            assertThat(fixture.sessions.getById(SESSION)).isEqualTo(initialDatabase)
        }
    }

    @Test
    fun `摘要失效阶段数据库失败时正文和归档标记随事务一起回滚`() = runTest {
        val fixture = fixture()
        val initialStore = fixture.store.get()
        val initialDatabase = fixture.sessions.getById(SESSION)
        fixture.database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_summary_invalidation BEFORE DELETE ON context_summaries
               BEGIN SELECT RAISE(ABORT, 'summary invalidation blocked'); END""",
        )

        val failure = runCatching { fixture.manager.editMessageContent(SESSION, OLD_USER, "事实B") }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(fixture.store.get()).isEqualTo(initialStore)
        assertThat(fixture.sessions.getById(SESSION)).isEqualTo(initialDatabase)
        assertThat(fixture.database.messageDao().getById(OLD_USER)?.content).isEqualTo("事实A")
        assertThat(fixture.database.messageDao().getById(OLD_USER)?.isArchived).isEqualTo(1)
    }

    @Test
    fun `编辑清除尚未执行的旧流式更新使延迟flush不能覆盖新正文`() = runTest {
        val fixture = fixture()
        fixture.manager.updateMessageContent(SESSION, OLD_USER, "旧队列正文")

        fixture.manager.editMessageContent(SESSION, OLD_USER, "事实B")
        advanceUntilIdle()

        assertThat(fixture.current().messages.first { it.id == OLD_USER }.content).isEqualTo("事实B")
        assertThat(fixture.database.messageDao().getById(OLD_USER)?.content).isEqualTo("事实B")
        assertThat(fixture.sessions.getById(SESSION)?.summary).isNull()
        assertThat(fixture.manager.coordinationState().purging).isEqualTo(0)
    }

    @Test
    fun `编辑等待在飞旧DBflush完成后再写入新正文且旧结果无法回流`() = runTest {
        val flushing = CompletableDeferred<Unit>()
        val releaseFlush = CompletableDeferred<Unit>()
        val editReachedRepository = CompletableDeferred<Unit>()
        val fixture = fixture(
            hooks = MessageManagerHooks(beforeDbWrite = { key ->
                if (key == "$SESSION:$OLD_USER") {
                    flushing.complete(Unit)
                    releaseFlush.await()
                }
            }),
            beforeEdit = { editReachedRepository.complete(Unit); Unit },
        )
        fixture.manager.updateMessageContent(SESSION, OLD_USER, "在飞旧正文")
        advanceTimeBy(600)
        runCurrent()
        flushing.await()
        val editing = async { fixture.manager.editMessageContent(SESSION, OLD_USER, "事实B") }

        runCurrent()
        val waitedForFlush = !editReachedRepository.isCompleted
        releaseFlush.complete(Unit)
        editing.await()
        advanceUntilIdle()

        assertThat(waitedForFlush).isTrue()
        assertThat(editReachedRepository.isCompleted).isTrue()
        assertThat(fixture.current().messages.first { it.id == OLD_USER }.content).isEqualTo("事实B")
        assertThat(fixture.database.messageDao().getById(OLD_USER)?.content).isEqualTo("事实B")
        assertThat(fixture.current().summary).isNull()
        assertThat(fixture.current().messages.all { !it.isArchived }).isTrue()
    }

    @Test
    fun `编辑在Store发布前持续持有上下文锁且合并时保留并发其它字段`() = runTest {
        val readingSnapshot = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val competingEntered = CompletableDeferred<Unit>()
        var pauseSnapshot = false
        val fixture = fixture(beforeSnapshotReturn = {
            if (pauseSnapshot) {
                readingSnapshot.complete(Unit)
                releaseSnapshot.await()
            }
        })
        pauseSnapshot = true
        val editing = async { fixture.manager.editMessageContent(SESSION, OLD_USER, "事实B") }
        readingSnapshot.await()
        var summaryAfterPublication: String? = SUMMARY
        val competing = async {
            withContextMutation(SESSION) {
                competingEntered.complete(Unit)
                summaryAfterPublication = fixture.current().summary
            }
        }
        runCurrent()
        val lockHeldThroughPublication = !competingEntered.isCompleted
        fixture.store.updateSession(SESSION) { session ->
            session.copy(
                title = "并发标题", draft = "并发草稿",
                messages = session.messages.map { if (it.id == CURRENT) it.copy(content = "并发当前内容") else it } +
                    Message("concurrent", MessageRole.ASSISTANT, "并发新增内容", createdAt = 5),
            )
        }
        releaseSnapshot.complete(Unit)
        editing.await()
        competing.await()

        val visible = fixture.current()
        assertThat(lockHeldThroughPublication).isTrue()
        assertThat(summaryAfterPublication).isNull()
        assertThat(visible.title).isEqualTo("并发标题")
        assertThat(visible.draft).isEqualTo("并发草稿")
        assertThat(visible.messages.single { it.id == OLD_USER }.content).isEqualTo("事实B")
        assertThat(visible.messages.single { it.id == CURRENT }.content).isEqualTo("并发当前内容")
        assertThat(visible.messages.single { it.id == "concurrent" }.content).isEqualTo("并发新增内容")
        assertThat(visible.messages.all { !it.isArchived }).isTrue()
    }

    private suspend fun TestScope.fixture(
        hooks: MessageManagerHooks = MessageManagerHooks(),
        beforeEdit: suspend () -> Unit = {},
        beforeSnapshotReturn: suspend () -> Unit = {},
    ): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries().build()
        databases += database
        return Fixture(database, context, this, hooks, beforeEdit, beforeSnapshotReturn).also { fixture ->
            fixture.sessions.create(Session(SESSION, "agent", title = "原始标题", draft = "原始草稿"))
            fixture.messages.insert(Message(OLD_USER, MessageRole.USER, "事实A", isArchived = true, createdAt = 1), SESSION)
            fixture.messages.insert(Message(OLD_ASSISTANT, MessageRole.ASSISTANT, "历史答复", isArchived = true, createdAt = 2), SESSION)
            fixture.messages.insert(Message(CURRENT, MessageRole.USER, "当前A", createdAt = 3), SESSION)
            database.contextSummaryDao().insert(ContextSummaryEntity(
                id = "summary", sessionId = SESSION, startMessageId = OLD_USER, endMessageId = OLD_ASSISTANT,
                summaryContent = SUMMARY, createdAt = 4,
            ))
            fixture.store.upsertSession(checkNotNull(fixture.sessions.getById(SESSION)))
        }
    }

    private class Fixture(
        val database: NexaraDatabase,
        context: Context,
        scope: CoroutineScope,
        hooks: MessageManagerHooks,
        beforeEdit: suspend () -> Unit,
        beforeSnapshotReturn: suspend () -> Unit,
    ) {
        val store = ChatStore()
        val sessions = object : SessionRepository(database.sessionDao(), database.messageDao()) {
            override suspend fun getById(id: String): Session? = super.getById(id).also { beforeSnapshotReturn() }
        }
        val messages = object : MessageRepository(database.messageDao()) {
            override suspend fun editContentInSession(sessionId: String, messageId: String, content: String) {
                beforeEdit()
                super.editContentInSession(sessionId, messageId, content)
            }
        }
        val manager = MessageManager(store, messages, sessions, scope, hooks)
        private val strategy = DefaultChatGenerationContentStrategy(
            context.getSharedPreferences("context-edit-${UUID.randomUUID()}", Context.MODE_PRIVATE), null,
        )

        fun current(): Session = checkNotNull(store.getSession(SESSION))
        fun protocolText(session: Session): String = strategy.buildProtocolMessages(
            session, "系统指令 ${session.summary.orEmpty()}", CURRENT,
        ).joinToString("\n") { it.content }
    }

    private companion object {
        const val SESSION = "edit-session"
        const val OLD_USER = "old-user"
        const val OLD_ASSISTANT = "old-assistant"
        const val CURRENT = "current-user"
        const val SUMMARY = "旧摘要包含事实A"
    }
}
