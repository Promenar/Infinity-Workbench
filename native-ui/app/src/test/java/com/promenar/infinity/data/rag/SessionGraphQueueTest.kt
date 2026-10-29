package com.promenar.infinity.data.rag

import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageQuote
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.toEntity

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 会话图谱任务的排入条件、后台执行结果与写入的对话证据。 */
@RunWith(RobolectricTestRunner::class)
class SessionGraphQueueTest {
    private lateinit var database: InfinityDatabase
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        preferences = context.getSharedPreferences("session-graph-test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `正文为空的引用消息能进入图谱抽取队列且包含批注`() = runTest {
        seedTurn(assistantContent = "这是一段足够长的助手回复，用于后台抽取实体与关系。")
        database.messageDao().update(Message("u1", MessageRole.USER, "", quotes = listOf(MessageQuote("source", "引文", "批注"))).toEntity("s1"))
        val queue = idleQueue()
        try {
            assertThat(RoomSessionGraphScheduler({ database }, { true }, { queue }).schedule("s1", "a1", null)).isTrue()
            assertThat(database.vectorizationTaskDao().getById(VectorizationQueue.sessionGraphTaskId("a1"))!!.userContent)
                .isEqualTo("> 引文\n\n批注")
        } finally { queue.shutdown() }
    }

    @Test
    fun `开关关闭或轮次过短时不排入任务`() = runTest {
        seedTurn(assistantContent = "Kotlin 由 JetBrains 开发，最早发布于 2011 年。")
        val failingQueue: suspend () -> VectorizationQueue = { error("不应排入") }

        assertThat(RoomSessionGraphScheduler({ database }, { false }, failingQueue).schedule("s1", "a1", "u1")).isFalse()

        database.messageDao().insert(MessageEntity(id = "u2", sessionId = "s1", role = "user", content = "嗯", createdAt = 2))
        database.messageDao().insert(MessageEntity(id = "a2", sessionId = "s1", role = "assistant", content = "好", createdAt = 3, parentMessageId = "u2"))
        assertThat(RoomSessionGraphScheduler({ database }, { true }, failingQueue).schedule("s1", "a2", null)).isFalse()
        assertThat(RoomSessionGraphScheduler({ database }, { true }, failingQueue).schedule("s2", "a1", "u1")).isFalse()
    }

    @Test
    fun `开启后排入以助手消息为键的任务，未指定用户消息时沿父链查找`() = runTest {
        seedTurn(assistantContent = "Kotlin 由 JetBrains 开发，最早发布于 2011 年。")
        val queue = idleQueue()
        val scheduler = RoomSessionGraphScheduler({ database }, { true }, { queue })

        assertThat(scheduler.schedule("s1", "a1", userMessageId = null)).isTrue()

        val row = database.vectorizationTaskDao().getById(VectorizationQueue.sessionGraphTaskId("a1"))!!
        assertThat(row.type).isEqualTo(VectorizationQueue.TYPE_SESSION_GRAPH)
        assertThat(row.status).isEqualTo("pending")
        assertThat(row.sessionId).isEqualTo("s1")
        assertThat(row.userMessageId).isEqualTo("u1")
        assertThat(row.userContent).isEqualTo("Kotlin 是谁开发的？")
        assertThat(queue.state.value.isDocumentIndexing).isFalse()
        queue.shutdown()
    }

    @Test
    fun `处理器把抽取结果写成带消息id的对话证据并记录今日调用`() = runTest {
        seedTurn(assistantContent = "Kotlin 由 JetBrains 开发，最早发布于 2011 年。")
        val protocol = mockk<LlmProtocol>()
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse(
            content = """{"nodes":[{"name":"Kotlin","type":"language"},{"name":"JetBrains","type":"organization"}],
                "edges":[{"source":"Kotlin","target":"JetBrains","relation":"developed_by","weight":0.9}]}""",
        )
        val stats = SessionGraphStats(preferences) { "2026-10-21" }
        val processor = RoomSessionGraphProcessor({ database }, { testGraphExtractor(protocol, GraphStore(database)) }, stats)

        val incomplete = processor.process(task())

        assertThat(incomplete).isNull()
        val evidence = database.kgEvidenceDao().affectedByMessage("a1")
        assertThat(evidence).isNotEmpty()
        assertThat(evidence.map { it.sourceType to it.sessionId }.distinct()).containsExactly("session" to "s1")
        assertThat(database.kgEdgeDao().getRelationsForMessage("a1", 10).map { it.relation }).containsExactly("developed_by")
        val today = stats.observe().first()
        assertThat(today.turns).isEqualTo(1)
        assertThat(today.calls).isEqualTo(1)
        coVerify(exactly = 1) { protocol.sendPromptSync(any()) }
    }

    @Test
    fun `抽取模型失败时处理器抛出且不写证据`() = runTest {
        seedTurn(assistantContent = "Kotlin 由 JetBrains 开发，最早发布于 2011 年。")
        val protocol = mockk<LlmProtocol>()
        coEvery { protocol.sendPromptSync(any()) } throws IllegalStateException("模型不可用")
        val processor = RoomSessionGraphProcessor(
            { database }, { testGraphExtractor(protocol, GraphStore(database)) }, SessionGraphStats(preferences),
        )

        val failure = runCatching { processor.process(task()) }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(database.kgEvidenceDao().affectedByMessage("a1")).isEmpty()
    }

    @Test
    fun `队列完成后删除任务行，未完成保留为部分完成，失败保留为失败`() = runBlocking {
        seedTurn(assistantContent = "Kotlin 由 JetBrains 开发，最早发布于 2011 年。")
        database.messageDao().insert(MessageEntity(id = "a2", sessionId = "s1", role = "assistant", content = "第二轮回复内容足够长用于抽取", createdAt = 3, parentMessageId = "u1"))
        database.messageDao().insert(MessageEntity(id = "a3", sessionId = "s1", role = "assistant", content = "第三轮回复内容足够长用于抽取", createdAt = 4, parentMessageId = "u1"))
        val outcomes = mapOf<String, () -> String?>(
            "a1" to { null },
            "a2" to { "已达到抽取预算上限" },
            "a3" to { throw IllegalStateException("抽取结果无效") },
        )
        val processed = mutableListOf<String>()
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = mockk(relaxed = true),
            graphExtractor = null,
            vectorDao = database.vectorDao(),
            vectorizationTaskDao = database.vectorizationTaskDao(),
            dispatcher = Dispatchers.Default,
            sessionGraph = { task ->
                synchronized(processed) { processed += task.assistantMessageId!! }
                outcomes.getValue(task.assistantMessageId!!).invoke()
            },
        ).apply { beforeFailureDisplayDelayForTest = {} }
        listOf("a1", "a2", "a3").forEach { id ->
            queue.enqueueSessionGraph("s1", "u1", id, "Kotlin 是谁开发的？", "回复 $id")
        }
        val dao = database.vectorizationTaskDao()
        withTimeout(10_000) {
            while (dao.getById(VectorizationQueue.sessionGraphTaskId("a1")) != null ||
                dao.getById(VectorizationQueue.sessionGraphTaskId("a2"))?.status != "partial" ||
                dao.getById(VectorizationQueue.sessionGraphTaskId("a3"))?.status != "failed"
            ) delay(20)
        }
        assertThat(synchronized(processed) { processed.toList() }).containsExactly("a1", "a2", "a3").inOrder()
        assertThat(dao.getById(VectorizationQueue.sessionGraphTaskId("a2"))!!.error).isEqualTo("已达到抽取预算上限")
        queue.shutdown()
    }

    private fun idleQueue() = VectorizationQueue(
        vectorStore = mockk(relaxed = true),
        embeddingClient = mockk(relaxed = true),
        graphExtractor = null,
        vectorDao = database.vectorDao(),
        vectorizationTaskDao = database.vectorizationTaskDao(),
        dispatcher = StandardTestDispatcher(TestCoroutineScheduler()),
        sessionGraph = { null },
    )

    private fun task() = VectorizationTask(
        id = VectorizationQueue.sessionGraphTaskId("a1"),
        type = VectorizationQueue.TYPE_SESSION_GRAPH,
        sessionId = "s1",
        userContent = "Kotlin 是谁开发的？",
        aiContent = "Kotlin 由 JetBrains 开发，最早发布于 2011 年。",
        userMessageId = "u1",
        assistantMessageId = "a1",
        status = "pending",
    )

    private suspend fun seedTurn(assistantContent: String) {
        database.sessionDao().insert(SessionEntity(id = "s1", agentId = "agent", createdAt = 1, updatedAt = 1))
        database.sessionDao().insert(SessionEntity(id = "s2", agentId = "agent", createdAt = 1, updatedAt = 1))
        database.messageDao().insert(MessageEntity(id = "u1", sessionId = "s1", role = "user", content = "Kotlin 是谁开发的？", createdAt = 1))
        database.messageDao().insert(
            MessageEntity(id = "a1", sessionId = "s1", role = "assistant", content = assistantContent, createdAt = 2, parentMessageId = "u1"),
        )
    }
}
