package com.promenar.infinity.data.model

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.domain.model.SessionRunMode
import kotlinx.serialization.encodeToString
import org.junit.Test

class SessionRunModeMapperTest {
    @Test fun `固定模式在实体领域和序列化往返中保留`() {
        SessionRunMode.entries.forEach { mode ->
            val entity = SessionEntity("s", "a", runMode = mode.name, createdAt = 1, updatedAt = 1)
            assertThat(entity.toDomain().runMode).isEqualTo(mode)
            assertThat(entity.toDomain().toEntity().runMode).isEqualTo(mode.name)
            assertThat(json.decodeFromString<Session>(json.encodeToString(entity.toDomain())).runMode).isEqualTo(mode)
        }
    }

    @Test fun `旧JSON和未知实体模式保持未确定`() {
        assertThat(json.decodeFromString<Session>("""{"id":"s","agentId":"a"}""").runMode).isNull()
        assertThat(SessionEntity("s", "a", runMode = "FUTURE", createdAt = 1, updatedAt = 1).toDomain().runMode).isNull()
        assertThat(Session("s", "a").toEntity().runMode).isNull()
    }
}
