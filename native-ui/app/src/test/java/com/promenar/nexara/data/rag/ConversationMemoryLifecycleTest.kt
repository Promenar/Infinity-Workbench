package com.promenar.nexara.data.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.KgEdgeEntity
import com.promenar.nexara.data.local.db.entity.KgEvidenceEntity
import com.promenar.nexara.data.local.db.entity.KgJitCacheEntity
import com.promenar.nexara.data.local.db.entity.KgNodeEntity
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.local.db.entity.VectorEntity
import java.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConversationMemoryLifecycleTest {
    private lateinit var database: NexaraDatabase
    private var now = 1_000L
    private val window = 10_000L
    private val writes = mutableListOf<List<String>>()
    private lateinit var lifecycle: RoomConversationMemoryLifecycle

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        lifecycle = RoomConversationMemoryLifecycle(
            databaseProvider = { database },
            turnWriter = { sessionId, user, assistant, userId, assistantId ->
                writes += listOf(sessionId, user, assistant, userId, assistantId)
                database.vectorDao().insert(memory("rewritten", sessionId, userId, assistantId, "$user / $assistant"))
            },
            clock = { now },
            undoWindowMillis = window,
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `删除回复后该轮记忆在向量与关键词检索中都不可见，恢复后重新可见`() = runTest {
        seedTurn()
        val dao = database.vectorDao()
        assertThat(dao.getBySessionIdAndType("s1", "memory").map { it.id }).containsExactly("mem")

        database.messageDao().deleteById("a1")
        lifecycle.onMessagesRemoved("s1")

        assertThat(dao.getBySessionIdAndType("s1", "memory")).isEmpty()
        assertThat(dao.getByType("memory")).isEmpty()
        assertThat(dao.getById("mem")).isNull()
        assertThat(dao.searchFtsBySession("needle", "s1")).isEmpty()
        assertThat(dao.searchFts("needle")).isEmpty()
        assertThat(dao.searchFtsExcludeDocs("needle")).isEmpty()

        database.messageDao().insert(assistant())
        lifecycle.onMessagesRestored("s1")

        assertThat(dao.getBySessionIdAndType("s1", "memory").map { it.id }).containsExactly("mem")
        assertThat(dao.searchFtsExcludeDocs("needle").map { it.id }).containsExactly("mem")
        assertThat(dao.getById("mem")?.stale).isFalse()
    }

    @Test
    fun `文档向量借用消息列存放文档id时不受消息存在性约束`() = runTest {
        seedTurn()
        database.vectorDao().insert(vector("doc-chunk", docId = "doc-1", sessionId = null, start = "doc-1", end = "doc-1",
            metadata = "{\"type\":\"document\",\"docId\":\"doc-1\"}"))

        assertThat(database.vectorDao().getByType("document").map { it.id }).containsExactly("doc-chunk")
    }

    @Test
    fun `孤儿记忆超过撤销窗口后物理删除，窗口内恢复则保留`() = runTest {
        seedTurn()
        database.messageDao().insert(MessageEntity(id = "u2", sessionId = "s1", role = "user", content = "second", createdAt = 3))
        database.messageDao().insert(MessageEntity(id = "a2", sessionId = "s1", role = "assistant", content = "reply", createdAt = 4))
        database.vectorDao().insert(memory("mem2", "s1", "u2", "a2", "needle two"))

        database.messageDao().deleteById("a1")
        database.messageDao().deleteById("a2")
        lifecycle.onMessagesRemoved("s1")

        now += window / 2
        database.messageDao().insert(MessageEntity(id = "a2", sessionId = "s1", role = "assistant", content = "reply", createdAt = 4))
        lifecycle.onMessagesRestored("s1")
        lifecycle.purgeExpired()
        assertThat(database.vectorDao().getBySessionId("s1").map { it.id }).containsExactly("mem2")

        now += window
        lifecycle.purgeExpired()
        val remaining = database.query("SELECT id FROM vectors", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertThat(remaining).containsExactly("mem2")
    }

    @Test
    fun `删除聊天记录后用过本会话记忆的即时图谱证据整体失效，文档证据保留`() = runTest {
        seedTurn()
        val kg = database.kgEvidenceDao()
        listOf("n-memory", "n-doc", "n-kept").forEach { database.kgNodeDao().insert(KgNodeEntity(it, it, createdAt = 1)) }
        database.kgEdgeDao().insert(KgEdgeEntity("e-memory", "n-memory", "n-doc", "related", createdAt = 1))
        kg.insert(evidence("jit-memory", "jit", nodeId = "n-memory", sessionId = "s1", docId = null, task = "task-1"))
        kg.insert(evidence("jit-memory-edge", "jit", edgeId = "e-memory", sessionId = "s1", docId = null, task = "task-1"))
        kg.insert(evidence("jit-doc-same-task", "jit", nodeId = "n-doc", sessionId = "s1", docId = "doc-1", task = "task-1"))
        kg.insert(evidence("jit-other-session", "jit", nodeId = "n-kept", sessionId = "s2", docId = null, task = "task-2"))
        kg.insert(evidence("document", "document", nodeId = "n-kept", sessionId = null, docId = "doc-1", task = "index"))
        database.kgJitCacheDao().insert(KgJitCacheEntity("cache", "q", "c", "{}", 1, Long.MAX_VALUE))

        database.messageDao().deleteById("a1")
        lifecycle.onMessagesRemoved("s1")

        val ids = database.query("SELECT id FROM kg_evidence", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertThat(ids).containsExactly("jit-other-session", "document")
        assertThat(database.kgNodeDao().getAll().map { it.id }).containsExactly("n-kept")
        assertThat(database.kgEdgeDao().getAll()).isEmpty()
        assertThat(database.kgJitCacheDao().getByKey("cache")).isNull()
    }

    @Test
    fun `编辑用户消息删除该轮记忆，编辑助手正文按新正文重写`() = runTest {
        seedTurn()
        database.messageDao().insert(MessageEntity(id = "u2", sessionId = "s1", role = "user", content = "second", createdAt = 3))
        database.messageDao().insert(MessageEntity(id = "a2", sessionId = "s1", role = "assistant", content = "old reply", createdAt = 4))
        database.vectorDao().insert(memory("mem2", "s1", "u2", "a2", "old reply"))

        lifecycle.onMessageEdited("s1", "u1")
        assertThat(database.vectorDao().getBySessionId("s1").map { it.id }).containsExactly("mem2")

        database.openHelper.writableDatabase.execSQL("UPDATE messages SET content = 'new reply' WHERE id = 'a2'")
        lifecycle.onMessageEdited("s1", "a2")

        assertThat(writes).containsExactly(listOf("s1", "second", "new reply", "u2", "a2"))
        assertThat(database.vectorDao().getBySessionId("s1").map { it.id }).containsExactly("rewritten")
    }

    @Test
    fun `重写失败时旧正文的记忆仍被删除`() = runTest {
        seedTurn()
        val failing = RoomConversationMemoryLifecycle(
            databaseProvider = { database },
            turnWriter = { _, _, _, _, _ -> error("embedding unavailable") },
            clock = { now },
        )

        failing.onMessageEdited("s1", "a1")

        assertThat(database.vectorDao().getBySessionId("s1")).isEmpty()
    }

    private suspend fun seedTurn() {
        database.sessionDao().insert(SessionEntity(id = "s1", agentId = "agent", createdAt = 1, updatedAt = 1))
        database.sessionDao().insert(SessionEntity(id = "s2", agentId = "agent", createdAt = 1, updatedAt = 1))
        database.messageDao().insert(MessageEntity(id = "u1", sessionId = "s1", role = "user", content = "question", createdAt = 1))
        database.messageDao().insert(assistant())
        database.vectorDao().insert(memory("mem", "s1", "u1", "a1", "User: question\nAssistant: needle"))
    }

    private fun assistant() = MessageEntity(id = "a1", sessionId = "s1", role = "assistant", content = "needle", createdAt = 2)

    private fun memory(id: String, sessionId: String, start: String, end: String, content: String) =
        vector(id, docId = null, sessionId = sessionId, start = start, end = end,
            metadata = "{\"type\":\"memory\",\"chunkIndex\":0}", content = content)

    private fun vector(
        id: String,
        docId: String?,
        sessionId: String?,
        start: String?,
        end: String?,
        metadata: String,
        content: String = "needle",
    ) = VectorEntity(
        id = id,
        docId = docId,
        sessionId = sessionId,
        content = content,
        embedding = ByteBuffer.allocate(8).also { it.asFloatBuffer().put(floatArrayOf(1f, 0f)) }.array(),
        metadata = metadata,
        startMessageId = start,
        endMessageId = end,
        createdAt = 1,
    )

    private fun evidence(
        id: String,
        type: String,
        nodeId: String? = null,
        edgeId: String? = null,
        sessionId: String?,
        docId: String?,
        task: String,
    ) = KgEvidenceEntity(id, nodeId, edgeId, type, docId, sessionId, "", task, 1)
}
