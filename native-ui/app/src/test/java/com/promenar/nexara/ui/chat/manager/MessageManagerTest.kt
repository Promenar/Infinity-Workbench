package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.*
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.ui.chat.ChatStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageManagerTest {
    private lateinit var store: ChatStore
    private lateinit var messageManager: MessageManager
    private val testScope = TestScope()
    private lateinit var sessionManager: SessionManager

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {}
        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {}
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): Session? = null
        override suspend fun getAll(): List<Session> = emptyList()
    }

    private val stubMessageRepo = object : IMessageRepository {
        override suspend fun insert(message: Message, sessionId: String) {}
        override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {}
        override suspend fun delete(messageId: String) {}
        override suspend fun deleteBySessionId(sessionId: String) {}
        override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) {}
        override suspend fun getById(messageId: String): Message? = null
        override suspend fun getBySession(sessionId: String): List<Message> = emptyList()
        override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) {}
    }

    @Before
    fun setUp() {
        store = ChatStore()
        messageManager = MessageManager(store, stubMessageRepo, stubSessionRepo, testScope)
        sessionManager = SessionManager(store, stubSessionRepo)
    }

    private suspend fun seedSession(id: String = "s1"): Session {
        val session = Session(id = id, agentId = "a1", title = "Test")
        sessionManager.addSession(session)
        testScope.advanceUntilIdle()
        return session
    }

    @Test
    fun addMessage() = testScope.runTest {
        seedSession()
        val msg = Message(id = "m1", role = MessageRole.USER, content = "hello", createdAt = 1000L)
        messageManager.addMessage("s1", msg)
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages).hasSize(1)
        assertThat(session.messages[0].content).isEqualTo("hello")
    }

    @Test
    fun deleteMessage() = testScope.runTest {
        seedSession()
        val msg = Message(id = "m1", role = MessageRole.USER, content = "hello", createdAt = 1000L)
        messageManager.addMessage("s1", msg)
        advanceUntilIdle()

        messageManager.deleteMessage("s1", "m1")
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages).isEmpty()
    }

    @Test
    fun deleteMessagesAfter() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.USER, content = "first", createdAt = 1000L))
        advanceUntilIdle()
        messageManager.addMessage("s1", Message(id = "m2", role = MessageRole.ASSISTANT, content = "second", createdAt = 2000L))
        advanceUntilIdle()
        messageManager.addMessage("s1", Message(id = "m3", role = MessageRole.USER, content = "third", createdAt = 3000L))
        advanceUntilIdle()

        messageManager.deleteMessagesAfter("s1", 2000L)
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages).hasSize(1)
        assertThat(session.messages[0].id).isEqualTo("m1")
    }

    @Test
    fun updateMessageProgress() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "hi", createdAt = 1000L))
        advanceUntilIdle()

        val progress = RagProgress(stage = "retrieving", percentage = 50)
        messageManager.updateMessageProgress("s1", "m1", progress)

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].ragProgress!!.stage).isEqualTo("retrieving")
        assertThat(session.messages[0].ragProgress!!.percentage).isEqualTo(50)
    }

    @Test
    fun updateMessageLayout() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "hi", createdAt = 1000L))
        advanceUntilIdle()

        messageManager.updateMessageLayout("s1", "m1", 200.0)

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].layoutHeight).isEqualTo(200.0)
    }

    @Test
    fun updateMessageLayoutIgnoresSmallChanges() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "hi", createdAt = 1000L, layoutHeight = 200.0))
        advanceUntilIdle()

        messageManager.updateMessageLayout("s1", "m1", 205.0)

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].layoutHeight).isEqualTo(200.0)
    }

    @Test
    fun setVectorizationStatus() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.USER, content = "hello", createdAt = 1000L))
        advanceUntilIdle()
        messageManager.addMessage("s1", Message(id = "m2", role = MessageRole.ASSISTANT, content = "world", createdAt = 2000L))
        advanceUntilIdle()

        messageManager.setVectorizationStatus("s1", listOf("m1", "m2"), "processing")
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].vectorizationStatus).isEqualTo("processing")
        assertThat(session.messages[1].vectorizationStatus).isEqualTo("processing")

        messageManager.setVectorizationStatus("s1", listOf("m1", "m2"), "success")
        advanceUntilIdle()

        val session2 = store.getSession("s1")!!
        assertThat(session2.messages[0].isArchived).isTrue()
        assertThat(session2.messages[1].isArchived).isTrue()
    }

    @Test
    fun updateMessageContentBuffersAndFlushes() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "", createdAt = 1000L))
        advanceUntilIdle()

        messageManager.updateMessageContent("s1", "m1", "hello world")
        assertThat(messageManager.hasPendingUpdates("s1", "m1")).isTrue()

        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].content).isEqualTo("hello world")
    }

    @Test
    fun updateMessageContentWithTokens() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "", createdAt = 1000L))
        advanceUntilIdle()

        val tokens = TokenUsage(input = 10, output = 20, total = 30)
        messageManager.updateMessageContent("s1", "m1", "response", UpdateMessageOptions(tokens = tokens))
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].tokens).isEqualTo(tokens)
        assertThat(session.messages[0].content).isEqualTo("response")
    }

    @Test
    fun updateMessageContentWithReasoning() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "", createdAt = 1000L))
        advanceUntilIdle()

        messageManager.updateMessageContent("s1", "m1", "result", UpdateMessageOptions(reasoning = "thinking..."))
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].reasoning).isEqualTo("thinking...")
    }

    @Test
    fun flushMessageUpdates() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "", createdAt = 1000L))
        advanceUntilIdle()

        messageManager.updateMessageContent("s1", "m1", "buffered content")
        assertThat(messageManager.hasPendingUpdates("s1", "m1")).isTrue()

        messageManager.flushMessageUpdates("s1", "m1")

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].content).isEqualTo("buffered content")
        assertThat(messageManager.hasPendingUpdates("s1", "m1")).isFalse()
    }
}
