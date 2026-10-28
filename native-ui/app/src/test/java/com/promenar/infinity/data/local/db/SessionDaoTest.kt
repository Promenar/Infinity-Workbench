package com.promenar.infinity.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.*
import com.promenar.infinity.data.local.db.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SessionDaoTest {
    private lateinit var db: InfinityDatabase
    private lateinit var dao: SessionDao

    @Test fun `待批准待回答及已回答仅阻挡目标会话系统轮`() = runBlocking {
        dao.insert(SessionEntity(id = "busy", agentId = "a", createdAt = 1, updatedAt = 1))
        dao.insert(SessionEntity(id = "other", agentId = "a", createdAt = 1, updatedAt = 1))
        val original = dao.getById("busy")!!
        for (waiting in listOf(
            original.copy(approvalRequest = "{\"id\":\"approval\"}"),
            original.copy(loopStatus = "waiting_for_input"),
            original.copy(loopStatus = "input_answered"),
        )) {
            dao.update(waiting)
            assertThat(dao.hasPendingUserInteraction("busy")).isTrue()
            assertThat(dao.hasPendingUserInteraction("other")).isFalse()
        }
        dao.update(original.copy(loopStatus = "running"))
        assertThat(dao.hasPendingUserInteraction("busy")).isFalse()
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.sessionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndGetById() = runBlocking {
        val session = SessionEntity(
            id = "s1",
            agentId = "agent1",
            title = "Test Session",
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        dao.insert(session)
        val result = dao.getById("s1")
        assertThat(result).isNotNull()
        assertThat(result!!.title).isEqualTo("Test Session")
        assertThat(result.agentId).isEqualTo("agent1")
    }

    @Test
    fun updateSession() = runBlocking {
        val session = SessionEntity(
            id = "s1",
            agentId = "agent1",
            title = "Old Title",
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        dao.insert(session)
        dao.update(session.copy(title = "New Title", updatedAt = 2000L))
        val result = dao.getById("s1")
        assertThat(result!!.title).isEqualTo("New Title")
        assertThat(result.updatedAt).isEqualTo(2000L)
    }

    @Test
    fun hasPendingApprovalIgnoresBlankRequests() = runBlocking {
        dao.insert(SessionEntity(id = "s1", agentId = "a", title = "t", createdAt = 1L, updatedAt = 1L, approvalRequest = ""))
        assertThat(dao.hasPendingApproval()).isFalse()
        dao.insert(SessionEntity(id = "s2", agentId = "a", title = "t", createdAt = 1L, updatedAt = 1L, approvalRequest = "{\"id\":\"x\"}"))
        assertThat(dao.hasPendingApproval()).isTrue()
    }

    @Test
    fun deleteById() = runBlocking {
        val session = SessionEntity(
            id = "s1",
            agentId = "agent1",
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        dao.insert(session)
        dao.deleteById("s1")
        val result = dao.getById("s1")
        assertThat(result).isNull()
    }

    @Test
    fun getAllReturnsOrderedByUpdatedAt() = runBlocking {
        val s1 = SessionEntity(id = "s1", agentId = "a1", createdAt = 1000L, updatedAt = 1000L)
        val s2 = SessionEntity(id = "s2", agentId = "a1", createdAt = 1000L, updatedAt = 3000L)
        val s3 = SessionEntity(id = "s3", agentId = "a1", createdAt = 1000L, updatedAt = 2000L)
        dao.insert(s1)
        dao.insert(s2)
        dao.insert(s3)
        val result = dao.getAll()
        assertThat(result.map { it.id }).containsExactly("s2", "s3", "s1").inOrder()
    }

    @Test
    fun updateTitle() = runBlocking {
        val session = SessionEntity(
            id = "s1",
            agentId = "a1",
            title = "Old",
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        dao.insert(session)
        dao.updateTitle("s1", "New", 2000L)
        val result = dao.getById("s1")
        assertThat(result!!.title).isEqualTo("New")
    }

    @Test
    fun fullSessionFields() = runBlocking {
        val session = SessionEntity(
            id = "s1",
            agentId = "agent1",
            title = "Full Session",
            lastMessage = "last msg",
            time = "2024-01-01",
            unread = 5,
            modelId = "gpt-4",
            customPrompt = "custom",
            isPinned = 1,
            scrollOffset = 42.5,
            draft = "draft text",
            executionMode = "manual",
            loopStatus = "running",
            pendingIntervention = """{"type":"approve"}""",
            approvalRequest = """{"tool":"read"}""",
            ragOptions = """{"enabled":true}""",
            inferenceParams = """{"temperature":0.7}""",
            activeTask = """{"id":"t1"}""",
            stats = """{"tokens":100}""",
            options = """{"webSearch":true}""",
            activeMcpServerIds = """["mcp1"]""",
            activeSkillIds = """["skill1"]""",
            workspacePath = "/workspace",
            createdAt = 1000L,
            updatedAt = 2000L,
        )
        dao.insert(session)
        val result = dao.getById("s1")
        assertThat(result).isNotNull()
        assertThat(result!!.agentId).isEqualTo("agent1")
        assertThat(result.lastMessage).isEqualTo("last msg")
        assertThat(result.unread).isEqualTo(5)
        assertThat(result.modelId).isEqualTo("gpt-4")
        assertThat(result.isPinned).isEqualTo(1)
        assertThat(result.executionMode).isEqualTo("manual")
        assertThat(result.loopStatus).isEqualTo("running")
        assertThat(result.workspacePath).isEqualTo("/workspace")
    }
}
