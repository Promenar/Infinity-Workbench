package com.promenar.nexara.data.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.local.db.entity.VectorEntity
import com.promenar.nexara.data.model.RagReferenceKind
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 对话记录按消息时间注入、按时间先后排列，新近度加分、按时间检索与当前状态。 */
@RunWith(RobolectricTestRunner::class)
class MemoryManagerTimelineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = time(2026, 10, 22, 10, 5)
    private val lastTuesday = time(2026, 10, 13, 9, 0)
    private val yesterday = time(2026, 10, 21, 20, 0)
    private lateinit var database: NexaraDatabase
    private lateinit var vectorStore: VectorStore
    private lateinit var embedding: EmbeddingClient

    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.sessionDao().insert(SessionEntity(id = "s1", agentId = "agent", createdAt = 1L, updatedAt = 1L))
        turn("1", lastTuesday)
        turn("2", yesterday)
        vectorStore = VectorStore(database.vectorDao(), database.kgNodeDao(), database.kgEdgeDao(), database.kgEvidenceDao())
        embedding = mockk<EmbeddingClient>()
        every { embedding.isConfigured } returns true
        every { embedding.hasLocalFallback } returns false
        coEvery { embedding.embedQuery(any()) } returns (floatArrayOf(1f, 0f) to null)
        Unit
    }

    @After
    fun tearDown() = database.close()

    private suspend fun turn(n: String, at: Long) {
        database.messageDao().insert(MessageEntity(id = "u$n", sessionId = "s1", role = "user", content = "q$n", createdAt = at))
        database.messageDao().insert(MessageEntity(id = "a$n", sessionId = "s1", role = "assistant", content = "a$n", createdAt = at + 1_000L))
    }

    /** 写入时间刻意取当前真实时间，验证注入使用的是消息时间。 */
    private suspend fun memory(turn: String, content: String, vector: FloatArray) = vectorStore.addVectorRecords(listOf(
        VectorStore.NewVectorRecord(
            sessionId = "s1", content = content, embedding = vector,
            metadata = """{"type":"memory","chunkIndex":0}""", startMessageId = "u$turn", endMessageId = "a$turn",
        ),
    ))

    private suspend fun row(id: String, turn: String, metadata: String, content: String, at: Long, vector: FloatArray = floatArrayOf(0f, 1f)) =
        database.vectorDao().insertAll(listOf(VectorEntity(
            id = id, sessionId = "s1", content = content, embedding = blob(vector), metadata = metadata,
            startMessageId = "u$turn", endMessageId = "a$turn", createdAt = at,
        )))

    private suspend fun state(id: String, turn: String, subject: String, attribute: String, value: String?, at: Long) {
        val valueJson = value?.let { "\"$it\"" } ?: "null"
        val key = normalizeStateKey(subject, attribute)
        row(
            id, turn,
            """{"type":"state","key":"$key","subject":"$subject","attribute":"$attribute","value":$valueJson}""",
            "$subject · $attribute: ${value ?: "(no longer applies)"}", at,
        )
    }

    private fun blob(vector: FloatArray): ByteArray =
        ByteBuffer.allocate(vector.size * 4).apply { asFloatBuffer().put(vector) }.array()

    private fun manager(memoryLimit: Int = 10) = MemoryManager(
        vectorStore = vectorStore,
        keywordSearcher = KeywordSearcher(database.vectorDao()),
        graphStore = GraphStore(database),
        embeddingClient = embedding,
        ragConfig = RagConfiguration(
            enableQueryRewrite = false,
            enableHybridSearch = false,
            enableRerank = false,
            memoryThreshold = 0.1f,
            memoryLimit = memoryLimit,
            docLimit = 10,
            rerankTopK = 10,
            rerankFinalK = 10,
            summaryTemplate = "",
        ),
        clock = { now },
        zone = { zone },
    )

    private suspend fun MemoryManager.retrieve(query: String = "q") = retrieveContext(
        query = query,
        sessionId = "s1",
        options = MemoryManager.RetrieveOptions(enableMemory = true, enableDocs = false, sessionId = "s1"),
    )

    @Test
    fun `记忆写入消息时间并按时间先后注入，引用携带该时间`() = runTest {
        memory("2", "User: 改用 B", floatArrayOf(1f, 0f))
        memory("1", "User: 用 A", floatArrayOf(0.9f, 0.1f))

        val result = manager().retrieve()

        val context = result.context
        assertThat(context).contains("Current time: 2026-10-22 10:05 Thursday (Asia/Shanghai)")
        assertThat(context).contains("the newer one reflects the current state")
        val older = context.indexOf("[Memory · 2026-10-13 09:00 · 9 days ago]: User: 用 A")
        val newer = context.indexOf("[Memory · 2026-10-21 20:00 · yesterday]: User: 改用 B")
        assertThat(older).isAtLeast(0)
        assertThat(newer).isGreaterThan(older)
        // 引用仍按得分排列，时间取本轮用户消息。
        assertThat(result.references.map { it.content }).containsExactly("User: 改用 B", "User: 用 A").inOrder()
        assertThat(result.references.map { it.occurredAt }).containsExactly(yesterday, lastTuesday).inOrder()
    }

    @Test
    fun `相关度接近时较新的记录优先入选，差距明显时仍以相关度为准`() = runTest {
        memory("1", "old", floatArrayOf(1f, 0f))
        memory("2", "new", floatArrayOf(0.99f, 0.141f))
        assertThat(manager(memoryLimit = 1).retrieve().references.map { it.content }).containsExactly("new")

        database.vectorDao().deleteAll()
        memory("1", "old", floatArrayOf(1f, 0f))
        memory("2", "new", floatArrayOf(0.8f, 0.6f))
        assertThat(manager(memoryLimit = 1).retrieve().references.map { it.content }).containsExactly("old")
    }

    @Test
    fun `回顾某段时间时该时段的记录不受阈值限制并优先入选`() = runTest {
        memory("1", "上周二的内容", floatArrayOf(0f, 1f))
        memory("2", "昨天的内容", floatArrayOf(1f, 0f))

        val recalled = manager(memoryLimit = 1).retrieve("上周二我们聊了什么")
        assertThat(recalled.references.map { it.content }).containsExactly("上周二的内容")

        // 没有回顾意味的问题不按时间限定，低于阈值的记录不出现。
        val plain = manager(memoryLimit = 1).retrieve("上周二是几号")
        assertThat(plain.references.map { it.content }).containsExactly("昨天的内容")
    }

    @Test
    fun `每个对象属性只注入最新值，已撤销的不注入，没有相似记录时也注入`() = runTest {
        state("st-1", "1", "用户", "编辑器", "Vim", lastTuesday)
        state("st-2", "2", "用户", " 编辑器 ", "VS Code", yesterday)
        state("st-3", "1", "用户", "语言", "Kotlin", lastTuesday)
        state("st-4", "2", "用户", "语言", null, yesterday)

        val result = manager().retrieve()

        assertThat(result.context).contains("[State · updated 2026-10-21 20:00 · yesterday]: 用户 ·  编辑器 : VS Code")
        assertThat(result.context).doesNotContain("Vim")
        assertThat(result.context).doesNotContain("Kotlin")
        assertThat(result.references.map { it.kind }).containsExactly(RagReferenceKind.STATE)
        assertThat(result.references.single().occurredAt).isEqualTo(yesterday)
        assertThat(result.trace?.memoryCount).isEqualTo(1)
    }

    @Test
    fun `事件摘要与记忆一起按时间线注入`() = runTest {
        memory("1", "User: 用 A", floatArrayOf(1f, 0f))
        row("ev-2", "2", """{"type":"summary","kind":"event"}""", "决定改用 B", yesterday, floatArrayOf(1f, 0f))

        val result = manager().retrieve()

        assertThat(result.context.indexOf("[Event · 2026-10-21 20:00 · yesterday]: 决定改用 B"))
            .isGreaterThan(result.context.indexOf("[Memory · 2026-10-13 09:00"))
        assertThat(result.references.map { it.kind }).containsExactly(RagReferenceKind.MEMORY, RagReferenceKind.SUMMARY)
        assertThat(result.references.first { it.kind == RagReferenceKind.SUMMARY }.source).isEqualTo("对话事件")
    }
}
