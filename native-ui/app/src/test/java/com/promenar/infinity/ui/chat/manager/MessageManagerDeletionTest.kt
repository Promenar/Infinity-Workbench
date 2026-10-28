package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.repository.IMessageRepository
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.ui.chat.ChatStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageManagerDeletionTest {
    @Test
    fun `删除后一次同步摘要与归档且保留并发内容和会话字段`() = runTest {
        val removed = message("removed", archived = true)
        val survivor = message("survivor", archived = true)
        val initial = session(listOf(removed, survivor))
        val store = ChatStore().apply { upsertSession(initial) }
        val other = initial.copy(id = "other", summary = "other summary")
        store.upsertSession(other)
        val persisted = initial.copy(
            title = "旧持久化标题", draft = "旧草稿", summary = null,
            messages = listOf(survivor.copy(content = "旧持久化正文", isArchived = false)),
        )
        val sessions = SnapshotRepository(persisted) {
            store.updateSession("s1") { current ->
                current.copy(
                    title = "并发标题", draft = "并发草稿",
                    messages = current.messages.map { if (it.id == survivor.id) it.copy(content = "并发正文") else it } +
                        message("concurrent"),
                )
            }
        }
        val messages = DeletionRepository(initial.messages, persisted.messages)
        val observed = mutableListOf<Session>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.state.collect { state -> state.sessions.firstOrNull { it.id == "s1" }?.let(observed::add) }
        }

        MessageManager(store, messages, sessions, this).deleteMessage("s1", removed.id)

        val updated = checkNotNull(store.getSession("s1"))
        assertThat(updated.summary).isNull()
        assertThat(updated.messages.map { it.id }).containsExactly("survivor", "concurrent").inOrder()
        assertThat(updated.messages.first().isArchived).isFalse()
        assertThat(updated.messages.first().content).isEqualTo("并发正文")
        assertThat(updated.title).isEqualTo("并发标题")
        assertThat(updated.draft).isEqualTo("并发草稿")
        assertThat(store.getSession("other")).isEqualTo(other)
        assertThat(observed.any { snapshot ->
            snapshot.messages.none { it.id == removed.id } &&
                (snapshot.summary != null || snapshot.messages.any { it.isArchived })
        }).isFalse()
        assertThat(sessions.reads).isEqualTo(1)
    }

    @Test
    fun `删除最近消息保留持久摘要与剩余归档状态`() = runTest {
        val archived = message("archived", archived = true)
        val recent = message("recent")
        val initial = session(listOf(archived, recent))
        val persisted = initial.copy(messages = listOf(archived))
        val store = ChatStore().apply { upsertSession(initial) }

        MessageManager(
            store, DeletionRepository(initial.messages, persisted.messages), SnapshotRepository(persisted), this,
        ).deleteMessage("s1", recent.id)

        assertThat(store.getSession("s1")?.summary).isEqualTo(initial.summary)
        assertThat(store.getSession("s1")?.messages?.single()?.isArchived).isTrue()
    }

    @Test
    fun `时间截断清理时间点之前的级联子消息并同步摘要失效`() = runTest {
        val survivor = message("survivor", createdAt = 1, archived = true)
        val parent = message("parent", createdAt = 10)
        val child = message("child", createdAt = 2, archived = true).copy(parentMessageId = parent.id)
        val initial = session(listOf(survivor, child, parent))
        val persisted = initial.copy(summary = null, messages = listOf(survivor.copy(isArchived = false)))
        val store = ChatStore().apply { upsertSession(initial) }

        MessageManager(
            store, DeletionRepository(initial.messages, persisted.messages), SnapshotRepository(persisted), this,
        ).deleteMessagesAfter("s1", 10)

        assertThat(store.getSession("s1")?.messages?.map { it.id }).containsExactly("survivor")
        assertThat(store.getSession("s1")?.messages?.single()?.isArchived).isFalse()
        assertThat(store.getSession("s1")?.summary).isNull()
    }

    @Test
    fun `清空消息同时镜像空摘要并保留会话其它字段`() = runTest {
        val initial = session(listOf(message("archived", archived = true)))
        val store = ChatStore().apply { upsertSession(initial) }
        val persisted = initial.copy(summary = null, messages = emptyList())

        MessageManager(
            store, DeletionRepository(initial.messages, emptyList()), SnapshotRepository(persisted), this,
        ).clearMessages("s1")

        val updated = checkNotNull(store.getSession("s1"))
        assertThat(updated.messages).isEmpty()
        assertThat(updated.summary).isNull()
        assertThat(updated.lastMessage).isNull()
        assertThat(updated.title).isEqualTo(initial.title)
        assertThat(updated.draft).isEqualTo(initial.draft)
    }

    @Test
    fun `删除取消保留Store且不读取摘要快照`() = runTest {
        val initial = session(listOf(message("removed", archived = true)))
        val store = ChatStore().apply { upsertSession(initial) }
        val cancelled = CancellationException("取消删除")
        val messages = DeletionRepository(initial.messages, emptyList()).apply { failure = cancelled }
        val sessions = SnapshotRepository(initial.copy(summary = null, messages = emptyList()))

        val failure = runCatching {
            MessageManager(store, messages, sessions, this).deleteMessage("s1", "removed")
        }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancelled)
        assertThat(store.getSession("s1")).isEqualTo(initial)
        assertThat(sessions.reads).isEqualTo(0)
    }

    @Test
    fun `准备补偿在部分删除失败后同步已提交摘要并保留原异常`() = runTest {
        val first = message("first", archived = true)
        val second = message("second")
        val survivor = message("survivor", archived = true)
        val initial = session(listOf(first, second, survivor))
        val store = ChatStore().apply { upsertSession(initial) }
        val persisted = initial.copy(summary = null, messages = listOf(second, survivor.copy(isArchived = false)))
        val expectedFailure = CancellationException("取消第二次补偿")
        val messages = DeletionRepository(initial.messages, persisted.messages).apply {
            failureForId = second.id to expectedFailure
        }

        val failure = runCatching {
            MessageManager(store, messages, SnapshotRepository(persisted), this)
                .discardPreparedMessages("s1", listOf(first.id, second.id))
        }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(expectedFailure)
        assertThat(store.getSession("s1")?.messages?.map { it.id }).containsExactly("survivor")
        assertThat(store.getSession("s1")?.summary).isNull()
        assertThat(store.getSession("s1")?.messages?.single()?.isArchived).isFalse()
    }

    @Test
    fun `普通删除在Store发布完成前阻止新摘要提交`() = runTest {
        assertSummaryCommitWaitsForDeletion { it.deleteMessage("s1", "removed") }
    }

    @Test
    fun `时间截断在Store发布完成前阻止新摘要提交`() = runTest {
        assertSummaryCommitWaitsForDeletion { it.deleteMessagesAfter("s1", 10) }
    }

    @Test
    fun `撤销恢复从入库到Store发布始终持有上下文变更锁`() = runTest {
        val initial = session(emptyList()).copy(summary = null)
        val store = ChatStore().apply { upsertSession(initial) }
        val original = message("restored", archived = true)
        var inserted: Message? = null
        val inserting = CompletableDeferred<Unit>()
        val releaseInsert = CompletableDeferred<Unit>()
        val repository = object : IMessageRepository by DeletionRepository(emptyList(), emptyList()) {
            override suspend fun insert(message: Message, sessionId: String) {
                inserted = message
                inserting.complete(Unit)
                releaseInsert.await()
            }
        }
        val manager = MessageManager(store, repository, SnapshotRepository(initial), this)
        val restoring = launch { manager.restoreMessages("s1", listOf(original)) }
        runCurrent()
        inserting.await()
        val competingEntered = CompletableDeferred<Unit>()
        var published: Session? = null
        val compaction = launch {
            withContextMutation("s1") {
                competingEntered.complete(Unit)
                published = store.getSession("s1")
            }
        }
        runCurrent()
        try {
            assertThat(competingEntered.isCompleted).isFalse()
        } finally {
            releaseInsert.complete(Unit)
        }
        restoring.join()
        compaction.join()

        assertThat(inserted?.isArchived).isFalse()
        assertThat(published?.messages?.single()?.id).isEqualTo(original.id)
        assertThat(published?.messages?.single()?.isArchived).isFalse()
        assertThat(published?.summary).isNull()
        assertThat(original.isArchived).isTrue()
    }

    @Test
    fun `清空等待在飞摘要发布后再删除且不会复活摘要`() = runTest {
        assertDeletionWaitsForSummaryPublication(clearAll = true) { it.clearMessages("s1") }
    }

    @Test
    fun `准备补偿等待在飞摘要发布后再删除且不会复活摘要`() = runTest {
        assertDeletionWaitsForSummaryPublication(clearAll = false) {
            it.discardPreparedMessage("s1", "removed")
        }
    }

    private suspend fun TestScope.assertDeletionWaitsForSummaryPublication(
        clearAll: Boolean,
        delete: suspend (MessageManager) -> Unit,
    ) {
        val removed = message("removed")
        val survivor = message("survivor")
        val initial = session(listOf(removed, survivor)).copy(summary = null)
        val persisted = initial.copy(messages = if (clearAll) emptyList() else listOf(survivor))
        val store = ChatStore().apply { upsertSession(initial) }
        val deletionEntered = CompletableDeferred<Unit>()
        val delegate = DeletionRepository(initial.messages, persisted.messages)
        val messages = object : IMessageRepository by delegate {
            override suspend fun deleteBySessionId(sessionId: String) {
                deletionEntered.complete(Unit)
                delegate.deleteBySessionId(sessionId)
            }
            override suspend fun deleteInSession(sessionId: String, messageId: String): Boolean {
                deletionEntered.complete(Unit)
                return delegate.deleteInSession(sessionId, messageId)
            }
        }
        val manager = MessageManager(store, messages, SnapshotRepository(persisted), this)
        val summaryCommittedToDb = CompletableDeferred<Unit>()
        val releaseSummaryPublication = CompletableDeferred<Unit>()
        val compaction = launch {
            withContextMutation("s1") {
                summaryCommittedToDb.complete(Unit)
                releaseSummaryPublication.await()
                store.updateSession("s1") { current ->
                    current.copy(summary = "在飞摘要", messages = current.messages.map { it.copy(isArchived = true) })
                }
            }
        }
        runCurrent()
        summaryCommittedToDb.await()
        val deletion = launch { delete(manager) }
        runCurrent()
        try {
            assertThat(deletionEntered.isCompleted).isFalse()
        } finally {
            releaseSummaryPublication.complete(Unit)
        }
        compaction.join()
        deletion.join()

        val updated = checkNotNull(store.getSession("s1"))
        assertThat(updated.summary).isNull()
        assertThat(updated.messages.any { it.isArchived }).isFalse()
        assertThat(updated.messages.map { it.id })
            .containsExactlyElementsIn(persisted.messages.map { it.id })
    }

    private suspend fun TestScope.assertSummaryCommitWaitsForDeletion(
        delete: suspend (MessageManager) -> Unit,
    ) {
        val removed = message("removed", createdAt = 10, archived = true)
        val survivor = message("survivor", createdAt = 1, archived = true)
        val initial = session(listOf(survivor, removed))
        val persisted = initial.copy(summary = null, messages = listOf(survivor.copy(isArchived = false)))
        val store = ChatStore().apply { upsertSession(initial) }
        val readingSnapshot = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val sessions = SnapshotRepository(persisted) {
            readingSnapshot.complete(Unit)
            releaseSnapshot.await()
        }
        val manager = MessageManager(
            store, DeletionRepository(initial.messages, persisted.messages), sessions, this,
        )
        val deletion = launch { delete(manager) }
        runCurrent()
        readingSnapshot.await()
        var summaryCommitted = false
        val compaction = launch {
            withContextMutation("s1") {
                summaryCommitted = true
                store.updateSession("s1") { current ->
                    current.copy(summary = "新摘要", messages = current.messages.map { it.copy(isArchived = true) })
                }
            }
        }
        runCurrent()
        try {
            assertThat(summaryCommitted).isFalse()
        } finally {
            releaseSnapshot.complete(Unit)
        }
        deletion.join()
        compaction.join()

        assertThat(store.getSession("s1")?.summary).isEqualTo("新摘要")
        assertThat(store.getSession("s1")?.messages?.single()?.id).isEqualTo(survivor.id)
        assertThat(store.getSession("s1")?.messages?.single()?.isArchived).isTrue()
    }

    @Test
    fun `删除丢弃截断清空都会同步对话记忆，删除失败与记忆同步失败互不影响`() = runTest {
        val first = message("first", createdAt = 1)
        val second = message("second", createdAt = 2)
        val initial = session(listOf(first, second))
        val store = ChatStore().apply { upsertSession(initial) }
        val memory = RecordingMemoryLifecycle()
        // 剩余列表保留 second，使随后的丢弃路径确有删除。
        val messages = DeletionRepository(initial.messages, listOf(second))
        val manager = MessageManager(store, messages, SnapshotRepository(initial.copy(messages = emptyList())), this,
            memoryLifecycle = memory)

        manager.deleteMessage("s1", first.id)
        manager.discardPreparedMessages("s1", listOf(second.id))
        manager.deleteMessagesAfter("s1", 0)
        manager.clearMessages("s1")
        advanceUntilIdle()
        assertThat(memory.events).containsExactly("removed:s1", "removed:s1", "removed:s1", "removed:s1").inOrder()

        messages.failure = IllegalStateException("db down")
        runCatching { manager.deleteMessages("s1", listOf(first.id)) }
        advanceUntilIdle()
        assertThat(memory.events).hasSize(4)

        messages.failure = null
        memory.failure = IllegalStateException("embedding down")
        store.upsertSession(initial)
        manager.deleteMessage("s1", second.id)
        advanceUntilIdle()
        assertThat(store.getSession("s1")?.messages?.map { it.id }).doesNotContain(second.id)
    }

    @Test
    fun `撤销删除后通知对话记忆恢复`() = runTest {
        val store = ChatStore().apply { upsertSession(session(emptyList())) }
        val memory = RecordingMemoryLifecycle()
        val restored = mutableListOf<String>()
        val repository = object : IMessageRepository {
            override suspend fun insert(message: Message, sessionId: String) { restored += message.id }
            override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) = Unit
            override suspend fun delete(messageId: String) = Unit
            override suspend fun deleteBySessionId(sessionId: String) = Unit
            override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) = Unit
            override suspend fun getById(messageId: String): Message? = null
            override suspend fun getBySession(sessionId: String): List<Message> = emptyList()
            override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) = Unit
        }
        MessageManager(store, repository, SnapshotRepository(session(emptyList())), this, memoryLifecycle = memory)
            .restoreMessages("s1", listOf(message("back")))
        advanceUntilIdle()

        assertThat(restored).containsExactly("back")
        assertThat(memory.events).containsExactly("restored:s1")
    }

    private class RecordingMemoryLifecycle : com.promenar.infinity.data.rag.ConversationMemoryLifecycle {
        val events = mutableListOf<String>()
        var failure: Throwable? = null
        override suspend fun onMessagesRemoved(sessionId: String) { failure?.let { throw it }; events += "removed:$sessionId" }
        override suspend fun onMessagesRestored(sessionId: String) { events += "restored:$sessionId" }
        override suspend fun onMessageEdited(sessionId: String, messageId: String): Map<String, com.promenar.infinity.data.model.TurnMemoryState> {
            events += "edited:$messageId"
            return emptyMap()
        }
        override suspend fun purgeExpired() = Unit
        override suspend fun removeTurn(sessionId: String, assistantMessageId: String) = 0
        override suspend fun rewriteTurn(sessionId: String, assistantMessageId: String) = 0
        override suspend fun turnChunkCount(sessionId: String, assistantMessageId: String) = 0
    }

    private fun session(messages: List<Message>) = Session(
        id = "s1", agentId = "agent", title = "当前标题", draft = "当前草稿",
        summary = "历史摘要", messages = messages, lastMessage = "最后一条",
    )

    private fun message(id: String, createdAt: Long = 1, archived: Boolean = false) =
        Message(id, MessageRole.ASSISTANT, id, createdAt = createdAt, isArchived = archived)

    private class SnapshotRepository(
        private val snapshot: Session,
        private val onRead: suspend () -> Unit = {},
    ) : ISessionRepository {
        var reads = 0
        override suspend fun getById(id: String): Session {
            reads += 1
            onRead()
            return snapshot
        }
        override suspend fun create(session: Session) = error("删除测试不创建会话")
        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) = error("删除测试不写会话字段")
        override suspend fun delete(id: String) = error("删除测试不删除会话")
        override suspend fun getAll(): List<Session> = error("删除测试不加载会话列表")
    }

    private class DeletionRepository(
        private var messages: List<Message>,
        private val remaining: List<Message>,
    ) : IMessageRepository {
        var failure: Throwable? = null
        var failureForId: Pair<String, Throwable>? = null
        override suspend fun getBySession(sessionId: String): List<Message> = messages
        override suspend fun getById(messageId: String): Message? = messages.firstOrNull { it.id == messageId }
        override suspend fun deleteManyInSession(sessionId: String, messageIds: List<String>): Int {
            failure?.let { throw it }
            val deleted = messages.count { it.id in messageIds }
            messages = remaining
            return deleted
        }
        override suspend fun deleteInSession(sessionId: String, messageId: String): Boolean {
            failure?.let { throw it }
            failureForId?.takeIf { it.first == messageId }?.let { throw it.second }
            val deleted = messages.any { it.id == messageId }
            messages = remaining
            return deleted
        }
        override suspend fun delete(messageId: String) { deleteInSession("s1", messageId) }
        override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) {
            failure?.let { throw it }
            messages = remaining
        }
        override suspend fun deleteBySessionId(sessionId: String) {
            failure?.let { throw it }
            messages = remaining
        }
        override suspend fun insert(message: Message, sessionId: String) = error("删除测试不插入消息")
        override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) = error("删除测试不修改消息正文")
        override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) =
            error("删除测试不另行写归档状态")
    }
}
