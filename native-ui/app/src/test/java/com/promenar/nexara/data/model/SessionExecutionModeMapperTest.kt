package com.promenar.nexara.data.model

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.SessionEntity
import org.junit.Test

class SessionExecutionModeMapperTest {
    @Test
    fun `entity unknown or blank execution mode maps to semi`() {
        listOf("", "   ", "future-mode").forEach { raw ->
            val session = SessionEntity(
                id = "session-$raw",
                agentId = "agent",
                executionMode = raw,
                createdAt = 1,
                updatedAt = 1,
            ).toDomain()

            assertThat(session.executionMode).isEqualTo("semi")
        }
    }

    @Test
    fun `domain unknown or blank execution mode persists as semi`() {
        listOf("", "   ", "future-mode").forEach { raw ->
            val entity = Session(
                id = "session-$raw",
                agentId = "agent",
                executionMode = raw,
            ).toEntity()

            assertThat(entity.executionMode).isEqualTo("semi")
        }
    }
}
