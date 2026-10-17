package com.promenar.nexara.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SessionRepositoryResilienceTest {
    private lateinit var database: NexaraDatabase
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = SessionRepository(database.sessionDao(), database.messageDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `损坏的会话JSON不影响其它会话和消息加载也不删除原行`() = runBlocking {
        seedSessions()
        database.sessionDao().update(session("broken", 2).copy(options = "{broken-json"))

        assertHealthySessions()

        assertThat(database.sessionDao().getById("broken")?.options).isEqualTo("{broken-json")
    }

    @Test
    fun `损坏的消息JSON仅跳过所属会话`() = runBlocking {
        seedSessions()
        database.messageDao().insert(message("broken").copy(toolCalls = "[broken-json"))

        assertHealthySessions()

        assertThat(database.messageDao().getById("message-broken")?.toolCalls).isEqualTo("[broken-json")
    }

    @Test
    fun `损坏行日志只含会话标识和异常类别不含正文或JSON`() = runBlocking {
        seedSessions()
        database.messageDao().insert(message("broken").copy(toolCalls = "[broken-json"))
        val output = ByteArrayOutputStream()
        val previous = System.err
        try {
            System.setErr(PrintStream(output))
            assertHealthySessions()
        } finally {
            System.setErr(previous)
        }

        val log = output.toString(Charsets.UTF_8.name())
        assertThat(log).contains("sessionId=broken")
        assertThat(log).contains("JsonDecodingException")
        assertThat(log).doesNotContain("正文-broken")
        assertThat(log).doesNotContain("[broken-json")
    }

    @Test
    fun `协程取消不作为损坏行跳过`() = runBlocking {
        val sessions = mockk<com.promenar.nexara.data.local.db.dao.SessionDao>()
        val cancelled = CancellationException("取消测试")
        coEvery { sessions.getAll() } returns listOf(session("cancelled", 1))
        coEvery { sessions.getContextSnapshot("cancelled") } throws cancelled
        val result = runCatching { SessionRepository(sessions, database.messageDao()).getAll() }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(cancelled)
    }

    private suspend fun seedSessions() {
        listOf(session("before", 3), session("broken", 2), session("after", 1)).forEach {
            database.sessionDao().insert(it)
            database.messageDao().insert(message(it.id))
        }
    }

    private suspend fun assertHealthySessions() {
        val loaded = repository.getAll()
        assertThat(loaded.map { it.id }).containsExactly("before", "after").inOrder()
        assertThat(loaded.map { it.messages.single().content }).containsExactly("正文-before", "正文-after").inOrder()
    }

    private fun session(id: String, time: Long) = SessionEntity(id = id, agentId = "agent", createdAt = time, updatedAt = time)
    private fun message(sessionId: String) = MessageEntity(
        id = "message-$sessionId", sessionId = sessionId, role = "user", content = "正文-$sessionId", createdAt = 1,
    )
}
