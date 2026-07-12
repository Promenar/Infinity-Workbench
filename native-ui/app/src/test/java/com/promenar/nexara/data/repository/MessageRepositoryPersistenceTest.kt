package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.toEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MessageRepositoryPersistenceTest {
    @Test
    fun `updatePartial目标行缺失时抛明确持久化异常`() = runTest {
        val dao = mockk<MessageDao>()
        coEvery { dao.getById("missing") } returns null
        val repository = MessageRepository(dao)

        val failure = runCatching {
            repository.updatePartial("missing", mapOf("status" to "success"))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(MessagePersistenceException::class.java)
        assertThat(failure?.message).contains("missing")
    }

    @Test
    fun `updatePartial affectedRows为零时抛明确持久化异常`() = runTest {
        val dao = mockk<MessageDao>()
        val entity = Message("m1", MessageRole.ASSISTANT, "partial").toEntity("s1")
        coEvery { dao.getById("m1") } returns entity
        coEvery { dao.update(any()) } returns 0
        val repository = MessageRepository(dao)

        val failure = runCatching {
            repository.updatePartial("m1", mapOf("status" to "success"))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(MessagePersistenceException::class.java)
        assertThat(failure?.message).contains("affectedRows=0")
    }
}
