package com.promenar.infinity.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.ContextSummaryEntity
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.model.ContextCompactionSource
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.toDomain
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.SessionManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ContextCompactionPersistenceTest {
    private lateinit var database: InfinityDatabase
    @Volatile
    private var queryObserver: ((String) -> Unit)? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(
                object : RoomDatabase.QueryCallback {
                    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                        queryObserver?.invoke(sqlQuery)
                    }
                },
                Executor { it.run() },
            )
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `引用在摘要生成期间变化时拒绝提交并保留新引用`() = runBlocking {
        seedSession()
        val messages = MessageRepository(database.messageDao())
        val first = com.promenar.infinity.data.model.MessageQuote("source", "引文", "批注A")
        messages.updatePartial("m1", mapOf("quotes" to listOf(first)))
        val source = sourceFor(listOf("m1", "m2"))
        messages.updatePartial("m1", mapOf("quotes" to listOf(first.copy(annotation = "批注B"))))

        val failure = runCatching { repository().commitContextCompaction(SESSION, "过期摘要", listOf("m1", "m2"), source) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(database.contextSummaryDao().getBySessionId(SESSION)).isEmpty()
        assertThat(database.messageDao().getById("m1")!!.isArchived).isEqualTo(0)
        assertThat(messages.getById("m1")!!.quotes!!.single().annotation).isEqualTo("批注B")
    }

    @Test
    fun `已归档消息的引用变化时同事务清除旧摘要并恢复原文`() = runBlocking {
        seedSession()
        repository().commitContextCompaction(SESSION, "旧摘要", listOf("m1", "m2"), sourceFor(listOf("m1", "m2")))
        MessageRepository(database.messageDao()).updatePartial("m1", mapOf("quotes" to listOf(
            com.promenar.infinity.data.model.MessageQuote("source", "引文", "补充批注"),
        )))

        assertThat(database.contextSummaryDao().getBySessionId(SESSION)).isEmpty()
        assertThat(database.messageDao().getBySession(SESSION).map { it.isArchived }).containsExactly(0, 0, 0)
        Unit
    }

    @Test
    fun `摘要与归档跨仓库实例恢复且原文和其它字段不变`() = runBlocking {
        val originals = seedSession()

        repository().commitContextCompaction(SESSION, "完整历史摘要", listOf("m2", "m1"), sourceFor(listOf("m2", "m1")))

        val recreated = repository()
        val restored = checkNotNull(recreated.getById(SESSION))
        assertThat(restored.summary).isEqualTo("完整历史摘要")
        assertThat(restored.messages).containsExactlyElementsIn(
            originals.map { it.toDomain().copy(isArchived = it.id != "m3") },
        ).inOrder()
        assertThat(recreated.getAll().single().summary).isEqualTo("完整历史摘要")
        val summary = database.contextSummaryDao().getBySessionId(SESSION).single()
        assertThat(summary.startMessageId).isEqualTo("m1")
        assertThat(summary.endMessageId).isEqualTo("m2")
    }

    @Test
    fun `连续压缩只保存当前聚合摘要并沿用有效的历史起点`() = runBlocking {
        seedSession()
        repository().commitContextCompaction(SESSION, "第一轮摘要", listOf("m1"), sourceFor(listOf("m1")))

        repository().commitContextCompaction(SESSION, "第一轮和第二轮合并摘要", listOf("m2"), sourceFor(listOf("m2")))

        val summaries = database.contextSummaryDao().getBySessionId(SESSION)
        assertThat(summaries).hasSize(1)
        assertThat(summaries.single().summaryContent).isEqualTo("第一轮和第二轮合并摘要")
        assertThat(summaries.single().startMessageId).isEqualTo("m1")
        assertThat(summaries.single().endMessageId).isEqualTo("m2")
    }

    @Test
    fun `归档写入失败时摘要和消息一同回滚且store保持原状态`() = runBlocking {
        seedSession()
        repository().commitContextCompaction(SESSION, "旧摘要", listOf("m1"), sourceFor(listOf("m1")))
        val oldSession = checkNotNull(repository().getById(SESSION))
        val oldRows = database.messageDao().getBySession(SESSION)
        val oldSummaries = database.contextSummaryDao().getBySessionId(SESSION)
        val store = ChatStore().apply { upsertSession(oldSession) }
        database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER fail_context_archive BEFORE UPDATE OF is_archived ON messages
               WHEN NEW.id = 'm3'
               BEGIN SELECT RAISE(ABORT, 'forced archive failure'); END""",
        )

        val failure = runCatching {
            SessionManager(store, repository()).commitContextCompaction(
                SESSION, "不应提交的新摘要", listOf("m2", "m3"),
                sourceFor(listOf("m2", "m3")),
            )
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(database.contextSummaryDao().getBySessionId(SESSION)).isEqualTo(oldSummaries)
        assertThat(database.messageDao().getBySession(SESSION)).isEqualTo(oldRows)
        assertThat(repository().getById(SESSION)?.summary).isEqualTo("旧摘要")
        assertThat(store.getSession(SESSION)).isEqualTo(oldSession)
    }

    @Test
    fun `跨会话缺失重复或空消息清单全部拒绝且没有部分提交`() = runBlocking {
        seedSession()
        seedSession("other", "other-")
        val before = database.messageDao().getBySession(SESSION)
        val cases = listOf(
            listOf("m1", "other-m1"),
            listOf("m1", "missing"),
            listOf("m1", "m1"),
            emptyList(),
        )

        cases.forEach { ids ->
            val failure = runCatching {
                repository().commitContextCompaction(SESSION, "摘要", ids, sourceFor(ids))
            }.exceptionOrNull()
            assertThat(failure).isNotNull()
            assertThat(database.contextSummaryDao().getBySessionId(SESSION)).isEmpty()
            assertThat(database.messageDao().getBySession(SESSION)).isEqualTo(before)
            assertThat(database.messageDao().getBySession("other").map { it.isArchived }).containsExactly(0, 0, 0)
        }
    }

    @Test
    fun `普通summary更新建立合法span且显式null清理持久和内存摘要`() = runBlocking {
        seedSession()
        repository().updatePartial(SESSION, mapOf("summary" to "写回摘要"))
        val persisted = checkNotNull(repository().getById(SESSION))
        assertThat(persisted.summary).isEqualTo("写回摘要")
        val summary = database.contextSummaryDao().getBySessionId(SESSION).single()
        assertThat(summary.startMessageId).isEqualTo("m1")
        assertThat(summary.endMessageId).isEqualTo("m3")
        val store = ChatStore().apply { upsertSession(persisted) }

        SessionManager(store, repository()).updateSession(SESSION, mapOf("summary" to null))

        assertThat(database.contextSummaryDao().getBySessionId(SESSION)).isEmpty()
        assertThat(repository().getById(SESSION)?.summary).isNull()
        assertThat(store.getSession(SESSION)?.summary).isNull()
    }

    @Test
    fun `无消息时拒绝非空摘要且同次普通字段更新回滚`() = runBlocking {
        database.sessionDao().insert(SessionEntity(SESSION, "agent", title = "旧标题", createdAt = 1, updatedAt = 1))

        val failure = runCatching {
            repository().updatePartial(SESSION, mapOf("title" to "新标题", "summary" to "没有合法跨度"))
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(database.sessionDao().getById(SESSION)?.title).isEqualTo("旧标题")
        assertThat(database.contextSummaryDao().getBySessionId(SESSION)).isEmpty()
    }

    @Test
    fun `读取不采用缺失跨会话或倒序端点的摘要`() = runBlocking {
        seedSession()
        seedSession("other", "other-")
        val invalidSummaries = listOf(
            Triple("m1", "other-m1", "跨会话跨度"),
            Triple("m1", "missing", "缺失端点"),
            Triple("m2", "m1", "倒序跨度"),
            Triple("m1", "m2", " \n "),
        )
        invalidSummaries.forEach { (start, end, content) ->
            database.contextSummaryDao().insert(ContextSummaryEntity(
                "invalid", SESSION, start, end, content, 1,
            ))
            database.openHelper.writableDatabase.execSQL(
                "UPDATE messages SET is_archived = 1 WHERE session_id = ?",
                arrayOf(SESSION),
            )

            val recovered = checkNotNull(repository().getById(SESSION))
            assertThat(recovered.summary).isNull()
            assertThat(recovered.messages.map { it.isArchived }).containsExactly(false, false, false)
            assertThat(database.contextSummaryDao().getBySessionId(SESSION)).isEmpty()
            assertThat(database.messageDao().getBySession(SESSION).map { it.isArchived }).containsExactly(0, 0, 0)
            assertThat(repository().getAll().single { it.id == SESSION }.messages.map { it.isArchived })
                .containsExactly(false, false, false)
        }
    }

    @Test
    fun `超过SQLite单次参数上限的历史仍能完整原子归档`() = runBlocking {
        database.sessionDao().insert(SessionEntity(SESSION, "agent", createdAt = 1, updatedAt = 1))
        val messages = (0 until 1050).map { index ->
            MessageEntity("long-$index", SESSION, "user", "原文-$index", createdAt = index.toLong())
        }
        database.messageDao().insertAll(messages)

        repository().commitContextCompaction(SESSION, "完整长历史摘要", messages.map { it.id }, sourceFor(messages.map { it.id }))

        assertThat(database.messageDao().getBySession(SESSION))
            .containsExactlyElementsIn(messages.map { it.copy(isArchived = 1) }).inOrder()
        val summary = database.contextSummaryDao().getBySessionId(SESSION).single()
        assertThat(summary.startMessageId).isEqualTo("long-0")
        assertThat(summary.endMessageId).isEqualTo("long-1049")
    }

    @Test
    fun `manager在真实提交后只发布一次同时包含摘要与归档的状态`() = runBlocking {
        seedSession()
        val initial = checkNotNull(repository().getById(SESSION))
        val store = ChatStore().apply { upsertSession(initial) }
        val observed = mutableListOf<Session>()
        val observer = launch(Dispatchers.Unconfined) {
            store.state.collect { state -> state.sessions.singleOrNull()?.let(observed::add) }
        }

        SessionManager(store, repository()).commitContextCompaction(
            SESSION, "完整摘要", listOf("m1", "m2"), sourceFor(listOf("m1", "m2")),
        )
        observer.cancelAndJoin()

        val expected = initial.copy(
            summary = "完整摘要",
            messages = initial.messages.map { it.copy(isArchived = it.id != "m3") },
        )
        assertThat(observed).containsExactly(initial, expected).inOrder()
        assertThat(repository().getById(SESSION)).isEqualTo(expected)
    }

    @Test
    fun `manager等待持久化成功后一次发布摘要及归档并保留最新其它字段`() = runBlocking {
        val original = seedSession().map { it.toDomain() }
        val initial = Session(SESSION, "agent", summary = "旧摘要", title = "原标题", messages = original)
        val store = ChatStore().apply { upsertSession(initial) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val suspendingRepository = object : ISessionRepository by repository() {
            override suspend fun commitContextCompaction(
                sessionId: String,
                summary: String,
                messageIds: List<String>,
                source: ContextCompactionSource,
            ) {
                assertThat(sessionId).isEqualTo(SESSION)
                assertThat(summary).isEqualTo("新摘要")
                assertThat(messageIds).containsExactly("m1", "m2").inOrder()
                assertThat(source.previousSummary).isEqualTo("旧摘要")
                entered.complete(Unit)
                release.await()
            }
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            SessionManager(store, suspendingRepository).commitContextCompaction(
                SESSION, "新摘要", listOf("m1", "m2"),
                ContextCompactionSource(initial.summary, original.take(2)),
            )
        }
        entered.await()
        assertThat(job.isCompleted).isFalse()
        assertThat(store.getSession(SESSION)).isEqualTo(initial)
        store.updateSession(SESSION) { session -> session.copy(title = "同步更新的标题") }

        release.complete(Unit)
        job.join()

        val committed = checkNotNull(store.getSession(SESSION))
        assertThat(committed.summary).isEqualTo("新摘要")
        assertThat(committed.title).isEqualTo("同步更新的标题")
        assertThat(committed.messages).containsExactlyElementsIn(
            original.map { it.copy(isArchived = it.id != "m3") },
        ).inOrder()
    }

    @Test
    fun `manager持久化取消时传播异常且不提前更新store`() = runBlocking {
        val initial = Session(SESSION, "agent", summary = "旧摘要", messages = seedSession().map { it.toDomain() })
        val store = ChatStore().apply { upsertSession(initial) }
        val cancellingRepository = object : ISessionRepository by repository() {
            override suspend fun commitContextCompaction(
                sessionId: String,
                summary: String,
                messageIds: List<String>,
                source: ContextCompactionSource,
            ) {
                throw CancellationException("cancel persistence")
            }
        }

        val failure = runCatching {
            SessionManager(store, cancellingRepository).commitContextCompaction(
                SESSION, "新摘要", listOf("m1"), ContextCompactionSource(initial.summary, initial.messages.take(1)),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(store.getSession(SESSION)).isEqualTo(initial)
    }

    @Test
    fun `旧数据库只有归档标记而没有持久摘要时恢复全部原始历史`() = runBlocking {
        val originals = seedSession()
        originals.take(2).forEach { database.messageDao().update(it.copy(isArchived = 1)) }

        val recovered = repository().getAll().single()

        assertThat(recovered.summary).isNull()
        assertThat(recovered.messages).containsExactlyElementsIn(originals.map { it.toDomain() }).inOrder()
        assertThat(database.messageDao().getBySession(SESSION)).containsExactlyElementsIn(originals).inOrder()
        repository().commitContextCompaction(
            SESSION, "恢复后重建摘要", listOf("m1", "m2"), sourceFor(listOf("m1", "m2")),
        )
        val compacted = checkNotNull(repository().getById(SESSION))
        assertThat(compacted.summary).isEqualTo("恢复后重建摘要")
        assertThat(compacted.messages.map { it.isArchived }).containsExactly(true, true, false).inOrder()
    }

    @Test
    fun `生成期间旧摘要因消息删除失效时拒绝发布过期摘要`() = runBlocking {
        seedSession()
        repository().commitContextCompaction(SESSION, "包含旧消息的摘要", listOf("m1"), sourceFor(listOf("m1")))
        val source = sourceFor(listOf("m2", "m3"))
        database.messageDao().deleteById("m1")
        database.contextSummaryDao().deleteBySessionId(SESSION)
        val afterDeletion = database.messageDao().getBySession(SESSION)

        val failure = runCatching {
            repository().commitContextCompaction(SESSION, "包含已删除事实的过期摘要", listOf("m2", "m3"), source)
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(database.contextSummaryDao().getBySessionId(SESSION)).isEmpty()
        assertThat(database.messageDao().getBySession(SESSION)).isEqualTo(afterDeletion)
        assertThat(afterDeletion.map { it.id }).containsExactly("m2", "m3").inOrder()
    }

    @Test
    fun `生成期间候选正文编辑时拒绝归档并保留编辑后的原文`() = runBlocking {
        val originals = seedSession()
        val source = sourceFor(listOf("m1", "m2"))
        val edited = originals.first().copy(content = "用户编辑后的原文")
        database.messageDao().update(edited)

        val failure = runCatching {
            repository().commitContextCompaction(SESSION, "基于过期正文的摘要", listOf("m1", "m2"), source)
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(database.contextSummaryDao().getBySessionId(SESSION)).isEmpty()
        assertThat(database.messageDao().getById("m1")).isEqualTo(edited)
        assertThat(database.messageDao().getById("m2")?.isArchived).isEqualTo(0)
    }

    @Test
    fun `候选角色归档和工具关系变化都使摘要快照失效`() = runBlocking {
        val originals = seedSession()
        val original = originals.first()
        val source = sourceFor(listOf("m1", "m2"))
        val changed = listOf(
            original.copy(role = "assistant"),
            original.copy(isArchived = 1),
            original.copy(toolCalls = """[{"id":"call","name":"read_file","arguments":"{}"}]"""),
            original.copy(toolCallId = "new-call"),
            original.copy(name = "new-tool"),
            original.copy(parentMessageId = "m2"),
        )

        changed.forEach { edited ->
            database.messageDao().update(edited)
            val failure = runCatching {
                repository().commitContextCompaction(SESSION, "过期摘要", listOf("m1", "m2"), source)
            }.exceptionOrNull()
            assertThat(failure).isNotNull()
            assertThat(database.contextSummaryDao().getBySessionId(SESSION)).isEmpty()
            assertThat(database.messageDao().getById("m1")).isEqualTo(edited)
            assertThat(database.messageDao().getById("m2")?.isArchived).isEqualTo(0)
            database.messageDao().update(original)
        }
    }

    @Test
    fun `候选快照ID必须与提交清单严格对应`() = runBlocking {
        seedSession()
        val source = sourceFor(listOf("m1", "m2"))
        val cases = listOf(
            source.copy(messages = source.messages.reversed()),
            source.copy(messages = source.messages.take(1)),
            source.copy(messages = source.messages + checkNotNull(database.messageDao().getById("m3")).toDomain()),
        )

        cases.forEach { invalidSource ->
            assertThat(runCatching {
                repository().commitContextCompaction(SESSION, "摘要", listOf("m1", "m2"), invalidSource)
            }.exceptionOrNull()).isNotNull()
            assertThat(database.contextSummaryDao().getBySessionId(SESSION)).isEmpty()
            assertThat(database.messageDao().getBySession(SESSION).map { it.isArchived }).containsExactly(0, 0, 0)
        }
    }

    @Test
    fun `费用向量化与布局元数据更新不误拒绝有效摘要`() = runBlocking {
        val originals = seedSession()
        val source = sourceFor(listOf("m1", "m2"))
        val withMetadata = originals.first().copy(
            tokens = """{"input":77,"output":3,"total":80}""",
            vectorizationStatus = "completed",
            layoutHeight = 48.0,
        )
        database.messageDao().update(withMetadata)

        repository().commitContextCompaction(SESSION, "有效摘要", listOf("m1", "m2"), source)

        assertThat(database.messageDao().getById("m1")).isEqualTo(withMetadata.copy(isArchived = 1))
        assertThat(repository().getById(SESSION)?.summary).isEqualTo("有效摘要")
    }

    @Test
    fun `单会话读取在摘要提交竞争中保持消息与摘要同一快照`() = runBlocking {
        assertConsistentReadSnapshot(loadAll = false)
    }

    @Test
    fun `会话列表读取在摘要提交竞争中保持每个会话快照一致`() = runBlocking {
        assertConsistentReadSnapshot(loadAll = true)
    }

    private suspend fun assertConsistentReadSnapshot(loadAll: Boolean) = kotlinx.coroutines.coroutineScope {
        seedSession()
        repository().commitContextCompaction(SESSION, "旧摘要", listOf("m1"), sourceFor(listOf("m1")))
        val nextSource = sourceFor(listOf("m2"))
        val readPaused = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val pauseNextSummaryRead = AtomicBoolean(true)
        val pausedInsideTransaction = AtomicBoolean(false)
        queryObserver = { sql ->
            if (sql.trimStart().startsWith("SELECT", ignoreCase = true) &&
                sql.contains("context_summaries", ignoreCase = true) &&
                pauseNextSummaryRead.compareAndSet(true, false)
            ) {
                pausedInsideTransaction.set(database.inTransaction())
                readPaused.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS)) { "摘要快照读取未获释放" }
            }
        }
        val reader = async(Dispatchers.IO) {
            if (loadAll) repository().getAll().single() else checkNotNull(repository().getById(SESSION))
        }
        var writer: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            check(readPaused.await(5, TimeUnit.SECONDS)) { "未到达摘要读取边界" }
            val writerStarted = CompletableDeferred<Unit>()
            writer = async(Dispatchers.IO) {
                writerStarted.complete(Unit)
                repository().commitContextCompaction(SESSION, "新摘要", listOf("m2"), nextSource)
            }
            writerStarted.await()
            // 给并发写入一个执行机会，读取事务必须保持之前已读到的归档状态。
            withTimeoutOrNull(1000) { checkNotNull(writer).await() }
            assertThat(pausedInsideTransaction.get()).isTrue()
        } finally {
            queryObserver = null
            releaseRead.countDown()
        }

        val snapshot = withTimeout(5000) { reader.await() }
        withTimeout(5000) { checkNotNull(writer).await() }
        assertThat(snapshot.summary).isEqualTo("旧摘要")
        assertThat(snapshot.messages.map { it.isArchived }).containsExactly(true, false, false).inOrder()
        val committed = checkNotNull(repository().getById(SESSION))
        assertThat(committed.summary).isEqualTo("新摘要")
        assertThat(committed.messages.map { it.isArchived }).containsExactly(true, true, false).inOrder()
    }

    private fun repository() = SessionRepository(database.sessionDao(), database.messageDao())

    private suspend fun sourceFor(messageIds: List<String>): ContextCompactionSource {
        val session = checkNotNull(repository().getById(SESSION))
        val messagesById = session.messages.associateBy { it.id }
        return ContextCompactionSource(
            previousSummary = session.summary,
            messages = messageIds.mapNotNull { id ->
                messagesById[id] ?: database.messageDao().getById(id)?.toDomain()
            },
        )
    }

    private suspend fun seedSession(sessionId: String = SESSION, prefix: String = ""): List<MessageEntity> {
        database.sessionDao().insert(SessionEntity(sessionId, "agent", createdAt = 1, updatedAt = 1))
        val messages = listOf(
            MessageEntity("${prefix}m1", sessionId, "user", "用户原文", reasoning = "原始推理", createdAt = 1),
            MessageEntity("${prefix}m2", sessionId, "assistant", "助手原文", modelId = "current::remote", createdAt = 2),
            MessageEntity("${prefix}m3", sessionId, "user", "当前轮原文", createdAt = 3),
        )
        database.messageDao().insertAll(messages)
        return messages
    }

    private companion object {
        const val SESSION = "session"
    }
}
