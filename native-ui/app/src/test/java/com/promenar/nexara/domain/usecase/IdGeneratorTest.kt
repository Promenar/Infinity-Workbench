package com.promenar.nexara.domain.usecase

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.jupiter.api.Test

class IdGeneratorTest {
    @Test fun `agent starts with agent_`() { assertThat(IdGenerator.agent()).startsWith("agent_") }
    @Test fun `session starts with session_`() { assertThat(IdGenerator.session()).startsWith("session_") }
    @Test fun `message starts with prefix`() { assertThat(IdGenerator.message("user")).startsWith("user_") }
    @Test fun `document starts with doc_`() { assertThat(IdGenerator.document()).startsWith("doc_") }
    @Test fun `folder starts with folder_`() { assertThat(IdGenerator.folder()).startsWith("folder_") }
    @Test fun `uuid is 36 chars`() { assertThat(IdGenerator.uuid()).hasLength(36) }
    @Test fun `uuids are unique`() {
        val ids = (1..100).map { IdGenerator.uuid() }.distinct()
        assertThat(ids).hasSize(100)
    }

    @Test
    fun `同一毫秒并发生成的消息ID仍全部唯一`() {
        val sequence = MonotonicIdSequence(clock = { 1234L })
        val executor = Executors.newFixedThreadPool(8)
        try {
            val ids = executor.invokeAll(
                List(1000) { Callable { sequence.next("ai") } },
            ).map { it.get() }

            assertThat(ids.toSet()).hasSize(1000)
        } finally {
            executor.shutdownNow()
        }
    }
}
