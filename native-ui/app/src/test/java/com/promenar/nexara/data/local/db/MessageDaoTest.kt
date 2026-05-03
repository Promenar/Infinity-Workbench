package com.promenar.nexara.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MessageDaoTest {
    private lateinit var db: NexaraDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var messageDao: MessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionDao = db.sessionDao()
        messageDao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertTestSession(id: String = "s1") {
        sessionDao.insert(SessionEntity(id = id, agentId = "a1", createdAt = 1000L, updatedAt = 1000L))
    }

    @Test
    fun insertAndGetById() = runBlocking {
        insertTestSession()
        val msg = MessageEntity(
            id = "m1", sessionId = "s1", role = "user",
            content = "Hello", createdAt = 1000L,
        )
        messageDao.insert(msg)
        val result = messageDao.getById("m1")
        assertThat(result).isNotNull()
        assertThat(result!!.content).isEqualTo("Hello")
        assertThat(result.role).isEqualTo("user")
    }

    @Test
    fun getBySessionOrderedByCreatedAt() = runBlocking {
        insertTestSession()
        val m1 = MessageEntity(id = "m1", sessionId = "s1", role = "user", content = "First", createdAt = 1000L)
        val m2 = MessageEntity(id = "m2", sessionId = "s1", role = "assistant", content = "Second", createdAt = 2000L)
        val m3 = MessageEntity(id = "m3", sessionId = "s1", role = "user", content = "Third", createdAt = 3000L)
        messageDao.insert(m3)
        messageDao.insert(m1)
        messageDao.insert(m2)
        val result = messageDao.getBySession("s1")
        assertThat(result.map { it.id }).containsExactly("m1", "m2", "m3").inOrder()
    }

    @Test
    fun countBySession() = runBlocking {
        insertTestSession()
        messageDao.insert(MessageEntity(id = "m1", sessionId = "s1", role = "user", content = "a", createdAt = 1000L))
        messageDao.insert(MessageEntity(id = "m2", sessionId = "s1", role = "assistant", content = "b", createdAt = 2000L))
        assertThat(messageDao.countBySession("s1")).isEqualTo(2)
    }

    @Test
    fun updateStatus() = runBlocking {
        insertTestSession()
        messageDao.insert(MessageEntity(id = "m1", sessionId = "s1", role = "user", content = "a", status = "sending", createdAt = 1000L))
        messageDao.updateStatus("m1", "sent")
        val result = messageDao.getById("m1")
        assertThat(result!!.status).isEqualTo("sent")
    }

    @Test
    fun deleteBySessionId() = runBlocking {
        insertTestSession("s1")
        insertTestSession("s2")
        messageDao.insert(MessageEntity(id = "m1", sessionId = "s1", role = "user", content = "a", createdAt = 1000L))
        messageDao.insert(MessageEntity(id = "m2", sessionId = "s2", role = "user", content = "b", createdAt = 1000L))
        messageDao.deleteBySessionId("s1")
        assertThat(messageDao.getById("m1")).isNull()
        assertThat(messageDao.getById("m2")).isNotNull()
    }

    @Test
    fun messageWithAllFields() = runBlocking {
        insertTestSession()
        val msg = MessageEntity(
            id = "m1", sessionId = "s1", role = "assistant",
            content = "response", modelId = "gpt-4", status = "sent",
            reasoning = "chain of thought", thoughtSignature = "sig123",
            images = """[{"url":"..."}]""", tokens = """{"total":50}""",
            citations = """[{"url":"..."}]""", ragReferences = """[{"doc":"d1"}]""",
            ragProgress = """{"percent":100}""", ragMetadata = """{"model":"embed"}""",
            ragReferencesLoading = 0, executionSteps = """[{"step":1}]""",
            toolCalls = """[{"id":"tc1"}]""", pendingApprovalToolIds = """["t1"]""",
            toolCallId = null, name = null, planningTask = """{"phase":"plan"}""",
            isArchived = 0, vectorizationStatus = "success",
            layoutHeight = 120.0, toolResults = """[{"artifact":"a1"}]""",
            files = """[{"uri":"file.pdf"}]""", isError = 0, errorMessage = null,
            createdAt = 1000L,
        )
        messageDao.insert(msg)
        val result = messageDao.getById("m1")
        assertThat(result).isNotNull()
        assertThat(result!!.modelId).isEqualTo("gpt-4")
        assertThat(result.reasoning).isEqualTo("chain of thought")
        assertThat(result.toolCalls).isEqualTo("""[{"id":"tc1"}]""")
        assertThat(result.isError).isEqualTo(0)
    }
}
