package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.TurnMemoryState
import com.promenar.nexara.data.model.TurnMemoryState.Status
import com.promenar.nexara.data.model.turnMemory
import com.promenar.nexara.data.rag.ConversationMemoryLifecycle
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.ui.chat.ChatStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageManagerTurnMemoryTest {
    @Test
    fun `写入本轮记忆时先记写入中，结束后按结果记为已记入或失败`() = runTest {
        val fixture = fixture()
        val gate = CompletableDeferred<Unit>()
        val write = async {
            fixture.manager.writeTurnMemory("s1", "a1", previous = null) {
                gate.await()
                stored(2)
            }
        }
        runCurrent()
        assertThat(fixture.storeState()?.status).isEqualTo(Status.WRITING)
        assertThat(fixture.dbState()?.status).isEqualTo(Status.WRITING)

        gate.complete(Unit)
        write.await()
        assertThat(fixture.storeState()).isEqualTo(stored(2))
        assertThat(fixture.dbState()).isEqualTo(stored(2))

        fixture.manager.writeTurnMemory("s1", "a1", previous = stored(2)) { error("embedding down") }
        assertThat(fixture.storeState()?.status).isEqualTo(Status.FAILED)
        assertThat(fixture.dbState()?.status).isEqualTo(Status.FAILED)
    }

    @Test
    fun `写入期间状态被移除时保留移除结果`() = runTest {
        val fixture = fixture()
        val removed = TurnMemoryState(Status.REMOVED, updatedAt = 7)

        fixture.manager.writeTurnMemory("s1", "a1", previous = null) {
            fixture.manager.setTurnMemoryState("s1", "a1", removed)
            stored(1)
        }

        assertThat(fixture.storeState()).isEqualTo(removed)
        assertThat(fixture.dbState()).isEqualTo(removed)
    }

    @Test
    fun `取消写入时按实际片段恢复：有片段记为已记入，没有片段恢复原状态`() = runTest {
        val fixture = fixture()
        val previous = TurnMemoryState(Status.FAILED, updatedAt = 3)
        fixture.manager.setTurnMemoryState("s1", "a1", previous)

        runCatching {
            fixture.manager.writeTurnMemory("s1", "a1", previous) { throw kotlinx.coroutines.CancellationException("stop") }
        }
        assertThat(fixture.storeState()).isEqualTo(previous)
        assertThat(fixture.dbState()).isEqualTo(previous)

        fixture.memory.chunks["a1"] = 2
        val failure = runCatching {
            fixture.manager.writeTurnMemory("s1", "a1", previous) { throw kotlinx.coroutines.CancellationException("stop") }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        assertThat(fixture.storeState()?.status to fixture.storeState()?.chunkCount).isEqualTo(Status.STORED to 2)
        assertThat(fixture.dbState()?.status).isEqualTo(Status.STORED)
    }

    @Test
    fun `修正没有写入在进行的写入中状态，进行中的写入不受影响`() = runTest {
        val writing = TurnMemoryState(Status.WRITING, updatedAt = 1).encode()
        val fixture = fixture(
            Message("a1", MessageRole.ASSISTANT, "one", vectorizationStatus = writing, createdAt = 2),
            Message("a2", MessageRole.ASSISTANT, "two", vectorizationStatus = writing, createdAt = 3),
            Message("a3", MessageRole.ASSISTANT, "three", createdAt = 4),
        )
        fixture.memory.chunks["a2"] = 3
        val gate = CompletableDeferred<Unit>()
        val active = async { fixture.manager.writeTurnMemory("s1", "a3", previous = null) { gate.await(); stored(1) } }
        runCurrent()

        fixture.manager.reconcileTurnMemory("s1")

        assertThat(fixture.storeState("a1")?.status).isEqualTo(Status.FAILED)
        assertThat(fixture.dbState("a1")?.status).isEqualTo(Status.FAILED)
        assertThat(fixture.storeState("a2")?.status to fixture.storeState("a2")?.chunkCount).isEqualTo(Status.STORED to 3)
        assertThat(fixture.storeState("a3")?.status).isEqualTo(Status.WRITING)
        gate.complete(Unit)
        active.await()
        assertThat(fixture.storeState("a3")).isEqualTo(stored(1))
    }

    @Test
    fun `撤销删除恢复写入中的快照后修正状态`() = runTest {
        val fixture = fixture()
        val snapshot = Message("back", MessageRole.ASSISTANT, "reply", createdAt = 5,
            vectorizationStatus = TurnMemoryState(Status.WRITING, updatedAt = 1).encode())
        fixture.memory.chunks["back"] = 1

        fixture.manager.restoreMessages("s1", listOf(snapshot))
        advanceUntilIdle()

        assertThat(fixture.storeState("back")?.status).isEqualTo(Status.STORED)
        assertThat(fixture.dbState("back")?.status).isEqualTo(Status.STORED)
    }

    @Test
    fun `摘要归档的向量化状态不覆盖本轮记忆状态`() = runTest {
        val fixture = fixture(
            Message("a1", MessageRole.ASSISTANT, "one", vectorizationStatus = stored(2).encode(), createdAt = 2),
            Message("u2", MessageRole.USER, "two", createdAt = 3),
        )

        fixture.manager.setVectorizationStatus("s1", listOf("a1", "u2"), "success")

        assertThat(fixture.storeState("a1")).isEqualTo(stored(2))
        assertThat(fixture.dbState("a1")).isEqualTo(stored(2))
        assertThat(fixture.repository.rows.getValue("u2").vectorizationStatus).isEqualTo("success")
        assertThat(fixture.store.getSession("s1")?.messages?.first { it.id == "u2" }?.vectorizationStatus).isEqualTo("success")
    }

    private fun stored(count: Int) = TurnMemoryState(Status.STORED, count, updatedAt = 10)

    private fun TestScope.fixture(
        vararg messages: Message = arrayOf(Message("a1", MessageRole.ASSISTANT, "reply", createdAt = 2)),
    ): Fixture {
        val session = Session(id = "s1", agentId = "agent", title = "t", messages = messages.toList())
        val store = ChatStore().apply { upsertSession(session) }
        val repository = MemoryRepository(messages.associateByTo(linkedMapOf()) { it.id })
        val memory = CountingLifecycle()
        val manager = MessageManager(store, repository, SessionRepository(session), this, memoryLifecycle = memory)
        return Fixture(store, repository, memory, manager)
    }

    private class Fixture(
        val store: ChatStore,
        val repository: MemoryRepository,
        val memory: CountingLifecycle,
        val manager: MessageManager,
    ) {
        fun storeState(id: String = "a1") = store.getSession("s1")?.messages?.firstOrNull { it.id == id }?.turnMemory
        fun dbState(id: String = "a1") = repository.rows[id]?.turnMemory
    }

    private class MemoryRepository(val rows: MutableMap<String, Message>) : IMessageRepository {
        override suspend fun insert(message: Message, sessionId: String) { rows[message.id] = message }
        override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {
            val row = rows[messageId] ?: error("missing $messageId")
            if ("vectorizationStatus" in updates) rows[messageId] = row.copy(vectorizationStatus = updates["vectorizationStatus"] as String?)
        }
        override suspend fun delete(messageId: String) { rows.remove(messageId) }
        override suspend fun deleteBySessionId(sessionId: String) = rows.clear()
        override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) = Unit
        override suspend fun getById(messageId: String): Message? = rows[messageId]
        override suspend fun getBySession(sessionId: String): List<Message> = rows.values.toList()
        override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) = Unit
    }

    private class CountingLifecycle : ConversationMemoryLifecycle {
        val chunks = mutableMapOf<String, Int>()
        override suspend fun onMessagesRemoved(sessionId: String) = Unit
        override suspend fun onMessagesRestored(sessionId: String) = Unit
        override suspend fun onMessageEdited(sessionId: String, messageId: String) = emptyMap<String, TurnMemoryState>()
        override suspend fun purgeExpired() = Unit
        override suspend fun removeTurn(sessionId: String, assistantMessageId: String) = 0
        override suspend fun rewriteTurn(sessionId: String, assistantMessageId: String) = 0
        override suspend fun turnChunkCount(sessionId: String, assistantMessageId: String) = chunks[assistantMessageId] ?: 0
    }

    private class SessionRepository(private val session: Session) : ISessionRepository {
        override suspend fun getById(id: String): Session = session
        override suspend fun create(session: Session) = error("不创建会话")
        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) = Unit
        override suspend fun delete(id: String) = error("不删除会话")
        override suspend fun getAll(): List<Session> = listOf(session)
    }
}
