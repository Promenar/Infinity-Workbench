package com.promenar.nexara.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.generation.DefaultChatGenerationContentStrategy
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.ContextSummaryEntity
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.MessageManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ContextSummaryDeletionTest {
    private lateinit var database: NexaraDatabase
    private lateinit var repository: MessageRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), NexaraDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = MessageRepository(database.messageDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `删除摘要中间归档消息清除聚合摘要并恢复剩余历史`() = runBlocking {
        seedHistory("s1")

        repository.delete("s1-middle")

        assertCleared("s1", listOf("s1-start", "s1-end", "s1-recent"))
    }

    @Test
    fun `删除尚未标为归档的摘要端点也清除聚合摘要`() = runBlocking {
        seedHistory("s1", archived = false)

        assertThat(repository.deleteInSession("s1", "s1-start")).isTrue()

        assertCleared("s1", listOf("s1-middle", "s1-end", "s1-recent"))
    }

    @Test
    fun `批量删除触及摘要只清理目标会话且忽略其它会话消息ID`() = runBlocking {
        seedHistory("s1")
        seedHistory("s2")

        assertThat(repository.deleteManyInSession("s1", listOf("s1-middle", "s2-end"))).isEqualTo(1)

        assertCleared("s1", listOf("s1-start", "s1-end", "s1-recent"))
        assertSummaryRetained("s2")
        assertThat(repository.getById("s2-end")).isNotNull()
    }

    @Test
    fun `未归档父消息级联删除摘要端点时清除聚合摘要`() = runBlocking {
        seedHistory("s1")
        val dao = database.messageDao()
        dao.insert(message("s1-parent", "s1", createdAt = 10))
        dao.update(checkNotNull(dao.getById("s1-end")).copy(parentMessageId = "s1-parent"))

        assertThat(repository.deleteInSession("s1", "s1-parent")).isTrue()

        assertCleared("s1", listOf("s1-start", "s1-middle", "s1-recent"))
    }

    @Test
    fun `时间截断的级联子消息早于截断点也使摘要失效`() = runBlocking {
        seedHistory("s1")
        val dao = database.messageDao()
        dao.insert(message("s1-parent", "s1", createdAt = 10))
        dao.update(checkNotNull(dao.getById("s1-end")).copy(parentMessageId = "s1-parent"))

        repository.deleteMessagesAfter("s1", 10)

        assertCleared("s1", listOf("s1-start", "s1-middle", "s1-recent"))
    }

    @Test
    fun `各公共局部删除路径删除最近未摘要消息时保留合法摘要`() = runBlocking {
        val paths: List<Pair<String, suspend (String) -> Unit>> = listOf(
            "unscoped" to { sessionId -> repository.delete("$sessionId-recent") },
            "scoped" to { sessionId -> repository.deleteInSession(sessionId, "$sessionId-recent"); Unit },
            "batch" to { sessionId -> repository.deleteManyInSession(sessionId, listOf("$sessionId-recent")); Unit },
            "after" to { sessionId -> repository.deleteMessagesAfter(sessionId, 4) },
        )
        for ((sessionId, delete) in paths) {
            seedHistory(sessionId)

            delete(sessionId)

            assertSummaryRetained(sessionId)
            assertThat(repository.getBySession(sessionId).map { it.id })
                .containsExactly("$sessionId-start", "$sessionId-middle", "$sessionId-end").inOrder()
        }
    }

    @Test
    fun `错误会话与空批量删除不清理任何摘要`() = runBlocking {
        seedHistory("s1")
        seedHistory("s2")

        assertThat(repository.deleteInSession("s2", "s1-start")).isFalse()
        assertThat(repository.deleteManyInSession("s1", emptyList())).isEqualTo(0)
        repository.delete("missing")

        assertSummaryRetained("s1")
        assertSummaryRetained("s2")
    }

    @Test
    fun `清空会话消息同时删除摘要且保留会话及其它会话`() = runBlocking {
        seedHistory("s1")
        seedHistory("s2")

        repository.deleteBySessionId("s1")

        assertCleared("s1", emptyList())
        assertThat(database.sessionDao().getById("s1")).isNotNull()
        assertSummaryRetained("s2")
    }

    @Test
    fun `超过绑定上限的批量删除在同一事务清理摘要`() = runBlocking {
        seedHistory("s1")
        val extras = (0 until 1_205).map { message("extra-$it", "s1", createdAt = 10L + it) }
        database.messageDao().insertAll(extras)

        repository.deleteManyInSession("s1", extras.map { it.id } + "s1-middle")

        assertCleared("s1", listOf("s1-start", "s1-end", "s1-recent"))
    }

    @Test
    fun `归档重置失败回滚消息删除和摘要清理`() = runBlocking {
        seedHistory("s1")
        database.openHelper.writableDatabase.execSQL(
            """CREATE TEMP TRIGGER reject_archive_reset BEFORE UPDATE OF is_archived ON messages
               WHEN NEW.is_archived = 0
               BEGIN SELECT RAISE(ABORT, 'archive reset rejected'); END""",
        )

        val failure = runCatching { repository.delete("s1-middle") }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(repository.getById("s1-middle")).isNotNull()
        assertSummaryRetained("s1")
    }

    @Test
    fun `批量后续分块失败回滚前序删除且保留摘要`() = runBlocking {
        seedHistory("s1")
        val extras = (0 until 1_005).map { message("extra-$it", "s1", createdAt = 10L + it) }
        database.messageDao().insertAll(extras)
        database.openHelper.writableDatabase.execSQL(
            """CREATE TEMP TRIGGER reject_late_delete BEFORE DELETE ON messages
               WHEN OLD.id = 'extra-1000'
               BEGIN SELECT RAISE(ABORT, 'later deletion rejected'); END""",
        )

        val failure = runCatching {
            repository.deleteManyInSession("s1", listOf("s1-middle") + extras.map { it.id })
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(repository.getBySession("s1")).hasSize(1_009)
        assertSummaryRetained("s1")
    }

    @Test
    fun `撤销已摘要轮删除恢复未归档原文且不复活失效摘要`() = runBlocking {
        seedHistory("s1")
        val dao = database.messageDao()
        dao.update(checkNotNull(dao.getById("s1-start")).copy(role = "user", content = "需要恢复的历史问题"))
        dao.update(checkNotNull(dao.getById("s1-middle")).copy(
            content = "需要恢复的历史回答", parentMessageId = "s1-start",
        ))
        dao.update(checkNotNull(dao.getById("s1-end")).copy(
            content = "需要恢复的历史补充", parentMessageId = "s1-start",
        ))
        dao.update(checkNotNull(dao.getById("s1-recent")).copy(role = "user", content = "当前问题"))
        val sessions = SessionRepository(database.sessionDao(), dao)
        val before = checkNotNull(sessions.getById("s1"))
        val undoSnapshot = before.messages.filter { it.isArchived }
        val store = ChatStore().apply { upsertSession(before) }
        val manager = MessageManager(store, repository, sessions, this)

        manager.deleteMessage("s1", "s1-start")
        assertThat(store.getSession("s1")?.summary).isNull()
        assertThat(sessions.getById("s1")?.summary).isNull()
        manager.restoreMessages("s1", undoSnapshot)

        val persisted = checkNotNull(sessions.getById("s1"))
        val displayed = checkNotNull(store.getSession("s1"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val strategy = DefaultChatGenerationContentStrategy(
            context.getSharedPreferences("context-summary-undo-test", Context.MODE_PRIVATE), null,
        )
        for (restored in listOf(persisted, displayed)) {
            assertThat(restored.summary).isNull()
            assertThat(restored.messages.any { it.isArchived }).isFalse()
            assertThat(restored.messages.map { it.id })
                .containsExactlyElementsIn(before.messages.map { it.id }).inOrder()
            val protocol = strategy.buildProtocolMessages(restored, "")
            assertThat(protocol.map { it.content })
                .containsExactlyElementsIn(before.messages.map { it.content }).inOrder()
        }
        assertThat(summaryCount("s1")).isEqualTo(0)
        assertThat(undoSnapshot.all { it.isArchived }).isTrue()
        assertNoDanglingSummary()
    }

    private suspend fun seedHistory(sessionId: String, archived: Boolean = true) {
        database.sessionDao().insert(SessionEntity(sessionId, "agent", createdAt = 1, updatedAt = 1))
        database.messageDao().insertAll(listOf(
            message("$sessionId-start", sessionId, createdAt = 1, archived = archived),
            message("$sessionId-middle", sessionId, createdAt = 2, archived = archived),
            message("$sessionId-end", sessionId, createdAt = 3, archived = archived),
            message("$sessionId-recent", sessionId, createdAt = 4),
        ))
        database.sessionDao().insertContextSummary(ContextSummaryEntity(
            id = "$sessionId-summary",
            sessionId = sessionId,
            startMessageId = "$sessionId-start",
            endMessageId = "$sessionId-end",
            summaryContent = "$sessionId summary",
            createdAt = 5,
        ))
    }

    private fun message(id: String, sessionId: String, createdAt: Long, archived: Boolean = false) =
        MessageEntity(id, sessionId, "assistant", id, isArchived = if (archived) 1 else 0, createdAt = createdAt)

    private suspend fun assertCleared(sessionId: String, remainingIds: List<String>) {
        val messages = repository.getBySession(sessionId)
        assertThat(messages.map { it.id }).containsExactlyElementsIn(remainingIds).inOrder()
        assertThat(messages.any { it.isArchived }).isFalse()
        assertThat(summaryCount(sessionId)).isEqualTo(0)
        assertNoDanglingSummary()
    }

    private suspend fun assertSummaryRetained(sessionId: String) {
        assertThat(database.sessionDao().getCurrentContextSummary(sessionId)?.summaryContent)
            .isEqualTo("$sessionId summary")
        assertThat(summaryCount(sessionId)).isEqualTo(1)
        assertThat(repository.getBySession(sessionId).count { it.isArchived }).isEqualTo(3)
        assertNoDanglingSummary()
    }

    private fun summaryCount(sessionId: String): Int = database.openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM context_summaries WHERE session_id = ?", arrayOf(sessionId),
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun assertNoDanglingSummary() {
        val dangling = database.openHelper.readableDatabase.query(
            """SELECT COUNT(*) FROM context_summaries AS summary
               WHERE NOT EXISTS (SELECT 1 FROM messages WHERE session_id = summary.session_id AND id = summary.start_message_id)
                  OR NOT EXISTS (SELECT 1 FROM messages WHERE session_id = summary.session_id AND id = summary.end_message_id)""",
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        assertThat(dangling).isEqualTo(0)
    }
}
