package com.promenar.infinity.data.model

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.SessionEntity
import org.junit.Test

class SessionExecutionModeMapperTest {
    @Test
    fun `全部旧模式读取并保存为两档新值`() {
        mapOf("auto" to "full_access", "full_access" to "full_access", "semi" to "workspace",
            "manual" to "workspace", "workspace" to "workspace", "" to "workspace", "unknown" to "workspace").forEach { (raw, expected) ->
            val session = SessionEntity(id = "session", agentId = "agent", executionMode = raw, createdAt = 1, updatedAt = 1).toDomain()
            assertThat(session.executionMode).isEqualTo(expected)
            assertThat(session.toEntity().executionMode).isEqualTo(expected)
            assertThat(Session(id = "session", agentId = "agent", executionMode = raw).toEntity().executionMode).isEqualTo(expected)
        }
    }

    @Test
    fun `entity unknown or blank execution mode maps to workspace`() {
        listOf("", "   ", "future-mode").forEach { raw ->
            val session = SessionEntity(
                id = "session-$raw",
                agentId = "agent",
                executionMode = raw,
                createdAt = 1,
                updatedAt = 1,
            ).toDomain()

            assertThat(session.executionMode).isEqualTo("workspace")
        }
    }

    @Test
    fun `domain unknown or blank execution mode persists as workspace`() {
        listOf("", "   ", "future-mode").forEach { raw ->
            val entity = Session(
                id = "session-$raw",
                agentId = "agent",
                executionMode = raw,
            ).toEntity()

            assertThat(entity.executionMode).isEqualTo("workspace")
        }
    }
}
