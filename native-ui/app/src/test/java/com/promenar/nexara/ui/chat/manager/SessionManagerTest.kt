package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.*
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.ui.chat.ChatStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {
    private lateinit var store: ChatStore
    private lateinit var sessionManager: SessionManager
    private val testScope = TestScope()

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {}
        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {}
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): Session? = null
        override suspend fun getAll(): List<Session> = emptyList()
    }

    @Before
    fun setUp() {
        store = ChatStore()
        sessionManager = SessionManager(store, stubSessionRepo)
    }

    @Test
    fun addSession() = testScope.runTest {
        val session = Session(id = "s1", agentId = "a1", title = "Test")
        sessionManager.addSession(session)
        advanceUntilIdle()

        val result = sessionManager.getSession("s1")
        assertThat(result).isNotNull()
        assertThat(result!!.title).isEqualTo("Test")
        assertThat(result.agentId).isEqualTo("a1")
    }

    @Test
    fun deleteSession() = testScope.runTest {
        val session = Session(id = "s1", agentId = "a1")
        sessionManager.addSession(session)
        advanceUntilIdle()

        sessionManager.deleteSession("s1")
        advanceUntilIdle()

        assertThat(sessionManager.getSession("s1")).isNull()
    }

    @Test
    fun updateSessionTitle() = testScope.runTest {
        val session = Session(id = "s1", agentId = "a1")
        sessionManager.addSession(session)
        advanceUntilIdle()

        sessionManager.updateSessionTitle("s1", "New Title")
        advanceUntilIdle()

        assertThat(sessionManager.getSession("s1")!!.title).isEqualTo("New Title")
    }

    @Test
    fun updateSessionDraft() = testScope.runTest {
        val session = Session(id = "s1", agentId = "a1")
        sessionManager.addSession(session)
        advanceUntilIdle()

        sessionManager.updateSessionDraft("s1", "hello")
        advanceUntilIdle()

        assertThat(sessionManager.getSession("s1")!!.draft).isEqualTo("hello")
    }

    @Test
    fun toggleSessionPin() = testScope.runTest {
        val session = Session(id = "s1", agentId = "a1", isPinned = false)
        sessionManager.addSession(session)
        advanceUntilIdle()

        sessionManager.toggleSessionPin("s1")
        advanceUntilIdle()

        assertThat(sessionManager.getSession("s1")!!.isPinned).isTrue()

        sessionManager.toggleSessionPin("s1")
        advanceUntilIdle()

        assertThat(sessionManager.getSession("s1")!!.isPinned).isFalse()
    }

    @Test
    fun updateSessionModel() = testScope.runTest {
        val session = Session(id = "s1", agentId = "a1")
        sessionManager.addSession(session)
        advanceUntilIdle()

        sessionManager.updateSessionModel("s1", "gpt-4o")
        advanceUntilIdle()

        val result = sessionManager.getSession("s1")!!
        assertThat(result.modelId).isEqualTo("gpt-4o")
        assertThat(result.options?.toolsEnabled).isTrue()
    }

    @Test
    fun getSessionsByAgent() = testScope.runTest {
        sessionManager.addSession(Session(id = "s1", agentId = "a1"))
        sessionManager.addSession(Session(id = "s2", agentId = "a2"))
        sessionManager.addSession(Session(id = "s3", agentId = "a1", isPinned = true))
        advanceUntilIdle()

        val sessions = sessionManager.getSessionsByAgent("a1")
        assertThat(sessions).hasSize(2)
        assertThat(sessions[0].isPinned).isTrue()
    }

    @Test
    fun toggleMcpServer() = testScope.runTest {
        val session = Session(id = "s1", agentId = "a1", activeMcpServerIds = listOf("m1"))
        sessionManager.addSession(session)
        advanceUntilIdle()

        sessionManager.toggleMcpServer("s1", "m2")
        advanceUntilIdle()
        assertThat(sessionManager.getSession("s1")!!.activeMcpServerIds).containsExactly("m1", "m2")

        sessionManager.toggleMcpServer("s1", "m1")
        advanceUntilIdle()
        assertThat(sessionManager.getSession("s1")!!.activeMcpServerIds).containsExactly("m2")
    }

    @Test
    fun toggleSkill() = testScope.runTest {
        val session = Session(id = "s1", agentId = "a1", activeSkillIds = listOf("sk1"))
        sessionManager.addSession(session)
        advanceUntilIdle()

        sessionManager.toggleSkill("s1", "sk2")
        advanceUntilIdle()
        assertThat(sessionManager.getSession("s1")!!.activeSkillIds).containsExactly("sk1", "sk2")

        sessionManager.toggleSkill("s1", "sk1")
        advanceUntilIdle()
        assertThat(sessionManager.getSession("s1")!!.activeSkillIds).containsExactly("sk2")
    }

    @Test
    fun dismissActiveTask() = testScope.runTest {
        val task = TaskState(id = "t1", title = "Task", status = "in-progress")
        val session = Session(id = "s1", agentId = "a1", activeTask = task)
        sessionManager.addSession(session)
        advanceUntilIdle()

        sessionManager.dismissActiveTask("s1")
        advanceUntilIdle()

        assertThat(sessionManager.getSession("s1")!!.activeTask).isNull()
    }
}
