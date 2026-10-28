package com.promenar.infinity.data.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.KgIdentity
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.dao.KgTurnCount
import com.promenar.infinity.data.local.db.dao.KgTurnRelation
import com.promenar.infinity.data.local.db.dao.SessionGraphTaskRow
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 会话图谱证据以所属助手消息存在为可见条件，编辑、删除与启动清理按 D1 规则维护。 */
@RunWith(RobolectricTestRunner::class)
class SessionGraphStoreTest {
    private lateinit var database: InfinityDatabase
    private val scheduled = mutableListOf<Triple<String, String, String?>>()
    private lateinit var lifecycle: RoomConversationMemoryLifecycle

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        lifecycle = RoomConversationMemoryLifecycle(
            databaseProvider = { database },
            turnWriter = { _, _, _, _, _ -> 0 },
            sessionGraph = { sessionId, assistantId, userId -> scheduled += Triple(sessionId, assistantId, userId); true },
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `写入一轮证据后按消息统计，删除消息即不可见，恢复后重新可见`() = runTest {
        seedTurn()
        assertThat(GraphStore(database).replaceTurn("s1", "a1", "hash", graph())).isTrue()

        assertThat(counts()).containsExactly(KgTurnCount("a1", 2, 1))
        assertThat(database.kgNodeDao().getForMessage("a1", 10).map { it.name }).containsExactly("Kotlin", "JetBrains")
        val relation = database.kgEdgeDao().getRelationsForMessage("a1", 10).single()
        assertThat(listOf(relation.source, relation.relation, relation.target)).containsExactly("Kotlin", "developed_by", "JetBrains").inOrder()
        val evidence = database.kgEvidenceDao().affectedByMessage("a1")
        assertThat(evidence.map { it.sourceType }.distinct()).containsExactly("session")
        assertThat(evidence.map { it.sessionId }.distinct()).containsExactly("s1")

        database.messageDao().deleteById("a1")
        assertThat(counts()).isEmpty()
        assertThat(database.kgNodeDao().getForMessage("a1", 10)).isEmpty()

        database.messageDao().insert(assistant())
        assertThat(counts()).containsExactly(KgTurnCount("a1", 2, 1))
    }

    @Test
    fun `重写一轮只保留新证据并回收不再有证据的实体`() = runTest {
        seedTurn()
        val store = GraphStore(database)
        store.replaceTurn("s1", "a1", "h1", graph())
        store.replaceTurn("s1", "a1", "h2", rustGraph())

        assertThat(counts()).containsExactly(KgTurnCount("a1", 2, 1))
        assertThat(database.kgNodeDao().getForMessage("a1", 10).map { it.name }).containsExactly("Rust", "Mozilla")
        assertThat(database.kgNodeDao().findIdentity(KgIdentity.normalize("Kotlin"), "language")).isNull()
    }

    @Test
    fun `消息已删除或不属于该会话时不写入证据`() = runTest {
        seedTurn()
        val store = GraphStore(database)
        assertThat(store.replaceTurn("s2", "a1", "hash", graph())).isFalse()
        database.messageDao().deleteById("a1")
        assertThat(store.replaceTurn("s1", "a1", "hash", graph())).isFalse()
        assertThat(database.kgEvidenceDao().affectedByMessage("a1")).isEmpty()
    }

    @Test
    fun `编辑助手正文清除旧证据并重新排入抽取，编辑用户消息只清除`() = runTest {
        seedTurn()
        GraphStore(database).replaceTurn("s1", "a1", "hash", graph())

        lifecycle.onMessageEdited("s1", "a1")
        assertThat(database.kgEvidenceDao().affectedByMessage("a1")).isEmpty()
        assertThat(scheduled).containsExactly(Triple("s1", "a1", null))

        GraphStore(database).replaceTurn("s1", "a1", "hash", graph())
        scheduled.clear()
        lifecycle.onMessageEdited("s1", "u1")
        assertThat(database.kgEvidenceDao().affectedByMessage("a1")).isEmpty()
        assertThat(scheduled).isEmpty()
    }

    @Test
    fun `没有抽取过的轮次编辑后不排入抽取`() = runTest {
        seedTurn()
        lifecycle.onMessageEdited("s1", "a1")
        assertThat(scheduled).isEmpty()
    }

    @Test
    fun `启动清理删除消息已不存在的证据与任务，保留仍有记忆片段引用的轮次`() = runTest {
        seedTurn()
        database.messageDao().insert(MessageEntity(id = "a2", sessionId = "s1", role = "assistant", content = "second", createdAt = 3))
        val store = GraphStore(database)
        store.replaceTurn("s1", "a1", "h1", graph())
        store.replaceTurn("s1", "a2", "h2", rustGraph())
        database.vectorizationTaskDao().insert(task("a1", "failed"))
        database.vectorizationTaskDao().insert(task("a2", "pending"))
        // a2 在撤销窗口内仍有记忆片段，图谱证据随记忆一起保留。
        database.vectorDao().insert(memoryVector("mem-a2", "a2"))
        database.messageDao().deleteById("a1")
        database.messageDao().deleteById("a2")

        lifecycle.purgeExpired()

        assertThat(database.kgEvidenceDao().affectedByMessage("a1")).isEmpty()
        assertThat(database.kgEvidenceDao().affectedByMessage("a2")).isNotEmpty()
        assertThat(database.kgNodeDao().findIdentity(KgIdentity.normalize("Kotlin"), "language")).isNull()
        assertThat(database.vectorizationTaskDao().getById(VectorizationQueue.sessionGraphTaskId("a1"))).isNull()
        assertThat(database.vectorizationTaskDao().getById(VectorizationQueue.sessionGraphTaskId("a2"))).isNull()
    }

    @Test
    fun `队列统计不把失败与未完成计入待处理`() = runTest {
        seedTurn()
        database.vectorizationTaskDao().insert(task("a1", "pending"))
        database.vectorizationTaskDao().insert(task("a2", "extracting"))
        database.vectorizationTaskDao().insert(task("a3", "failed"))
        database.vectorizationTaskDao().insert(task("a4", "partial"))

        val queue = database.vectorizationTaskDao().observeSessionGraphQueue().first()
        assertThat(queue.pending).isEqualTo(2)
        assertThat(queue.failed).isEqualTo(1)
        val rows = database.vectorizationTaskDao().observeSessionGraphTasks("s1").first()
        assertThat(rows.map { it.messageId }).containsExactly("a1", "a2", "a3", "a4")
    }

    @Test
    fun `任务行与证据合成每轮状态，任务优先，完成后按证据计数`() {
        val states = turnGraphStates(
            tasks = listOf(
                SessionGraphTaskRow("a1", "pending", null),
                SessionGraphTaskRow("a2", "extracting", null),
                SessionGraphTaskRow("a3", "failed", "超时"),
                SessionGraphTaskRow("a4", "partial", "预算"),
                SessionGraphTaskRow("a5", "completed", null),
                SessionGraphTaskRow(null, "pending", null),
            ),
            counts = listOf(KgTurnCount("a4", 3, 1), KgTurnCount("a6", 2, 2), KgTurnCount("a7", 0, 0)),
        )
        assertThat(states["a1"]?.status).isEqualTo(TurnGraphState.Status.QUEUED)
        assertThat(states["a2"]?.status).isEqualTo(TurnGraphState.Status.RUNNING)
        assertThat(states["a3"]).isEqualTo(TurnGraphState(TurnGraphState.Status.FAILED, error = "超时"))
        assertThat(states["a4"]).isEqualTo(TurnGraphState(TurnGraphState.Status.PARTIAL, 3, 1, "预算"))
        assertThat(states["a6"]).isEqualTo(TurnGraphState(TurnGraphState.Status.DONE, 2, 2))
        assertThat(states.keys).containsExactly("a1", "a2", "a3", "a4", "a6")
    }

    @Test
    fun `过短的轮次不值得抽取，超长的轮次截断`() {
        assertThat(SessionGraphTurn.text("hi", "ok")).isNull()
        assertThat(SessionGraphTurn.text("  ", "一段足够长的回复正文用于抽取知识图谱实体")).isNull()
        val text = SessionGraphTurn.text("问题".repeat(10), "回复".repeat(10_000))!!
        assertThat(text).startsWith("User: 问题")
        assertThat(text.length).isEqualTo(SessionGraphTurn.MAX_CHARS)
    }

    @Test
    fun `打开图谱定位到关系中出现最多的实体`() {
        val entities = listOf(TurnGraphEntity("Android", "platform"), TurnGraphEntity("Kotlin", "language"), TurnGraphEntity("JetBrains", "organization"))
        val relations = listOf(
            KgTurnRelation("e1", "Kotlin", "developed_by", "JetBrains"),
            KgTurnRelation("e2", "Android", "officially_supports", "Kotlin"),
        )
        assertThat(TurnGraphDetails(entities, relations).focusEntity).isEqualTo("Kotlin")
        assertThat(TurnGraphDetails(entities, emptyList()).focusEntity).isEqualTo("Android")
        assertThat(TurnGraphDetails(emptyList(), emptyList()).focusEntity).isNull()
    }

    private suspend fun counts() = database.kgEvidenceDao().observeTurnCounts("s1").first()

    private suspend fun seedTurn() {
        database.sessionDao().insert(SessionEntity(id = "s1", agentId = "agent", createdAt = 1, updatedAt = 1))
        database.sessionDao().insert(SessionEntity(id = "s2", agentId = "agent", createdAt = 1, updatedAt = 1))
        database.messageDao().insert(MessageEntity(id = "u1", sessionId = "s1", role = "user", content = "Kotlin 是谁开发的？", createdAt = 1))
        database.messageDao().insert(assistant())
    }

    private fun assistant() = MessageEntity(
        id = "a1", sessionId = "s1", role = "assistant", content = "Kotlin 由 JetBrains 开发。", createdAt = 2, parentMessageId = "u1",
    )

    private fun graph() = ValidatedGraph.from(
        listOf(ExtractedNode("Kotlin", "language"), ExtractedNode("JetBrains", "organization")),
        listOf(ExtractedEdge("Kotlin", "JetBrains", "developed_by")),
    )

    private fun rustGraph() = ValidatedGraph.from(
        listOf(ExtractedNode("Rust", "language"), ExtractedNode("Mozilla", "organization")),
        listOf(ExtractedEdge("Rust", "Mozilla", "developed_by")),
    )

    private fun task(messageId: String, status: String) = VectorizationTaskEntity(
        id = VectorizationQueue.sessionGraphTaskId(messageId),
        type = VectorizationQueue.TYPE_SESSION_GRAPH,
        status = status,
        sessionId = "s1",
        assistantMessageId = messageId,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun memoryVector(id: String, assistantId: String) = com.promenar.infinity.data.local.db.entity.VectorEntity(
        id = id,
        docId = null,
        sessionId = "s1",
        content = "memory",
        embedding = java.nio.ByteBuffer.allocate(4).array(),
        metadata = "{\"type\":\"memory\",\"chunkIndex\":0}",
        startMessageId = "u1",
        endMessageId = assistantId,
        createdAt = 1,
    )
}
