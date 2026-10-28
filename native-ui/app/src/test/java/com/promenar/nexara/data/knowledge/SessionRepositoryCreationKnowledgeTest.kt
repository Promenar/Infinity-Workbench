package com.promenar.nexara.data.knowledge

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.repository.SessionRepository
import com.promenar.nexara.domain.model.SessionRunMode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SessionRepositoryCreationKnowledgeTest {
    private val rows = mutableMapOf<String, SessionEntity>()
    private val sessions = mockk<SessionDao>().also { dao ->
        coEvery { dao.getById(any()) } answers { rows[firstArg()] }
        coEvery { dao.insert(any()) } answers { firstArg<SessionEntity>().let { rows[it.id] = it }; Unit }
        coEvery { dao.update(any()) } answers { firstArg<SessionEntity>().let { rows[it.id] = it }; Unit }
    }
    private val messages = mockk<MessageDao>()
    private val mounted = mutableSetOf<String>()
    private val created = mutableListOf<Session>()
    private val repository = SessionRepository(sessions, messages, onSessionCreated = { session ->
        assertThat(rows.containsKey(session.id)).isTrue()
        created += session
        mounted += session.id
    })

    @Test
    fun `会话持久化后才触发创建回调并带出实际运行模式`() = runTest {
        repository.create(Session("s", "a"))
        assertThat(created.map { it.id }).containsExactly("s")
        assertThat(created.single().runMode).isEqualTo(SessionRunMode.BUILTIN)
        assertThat(mounted).containsExactly("s")
    }

    @Test
    fun `相同会话重复create不会重新触发知识库挂载回调`() = runTest {
        val session = Session("s", "a")
        repository.create(session)
        mounted.clear()
        repository.create(session)
        assertThat(created).hasSize(1)
        assertThat(mounted).isEmpty()
    }

    @Test
    fun `用户移除挂载后普通会话更新不自动补回`() = runTest {
        val session = Session("s", "a")
        repository.create(session)
        mounted.clear()
        repository.update(session.copy(title = "新的标题"))
        repository.updatePartial("s", mapOf("draft" to "草稿"))
        assertThat(created).hasSize(1)
        assertThat(mounted).isEmpty()
    }

    @Test
    fun `创建回调失败不影响会话创建`() = runTest {
        val failing = SessionRepository(sessions, messages, onSessionCreated = { error("知识库不可用") })
        failing.create(Session("f", "a"))
        assertThat(rows).containsKey("f")
        val domain = failing.create("a", "model")
        assertThat(rows).containsKey(domain.id)
    }

    @Test
    fun `领域接口新建会话也触发同一创建回调`() = runTest {
        val createdSession = repository.create("a", "model")
        assertThat(created.map { it.id }).containsExactly(createdSession.id)
        assertThat(mounted).containsExactly(createdSession.id)
    }
}
