package com.promenar.nexara.domain.usecase

import com.google.common.truth.Truth.assertThat
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
}
