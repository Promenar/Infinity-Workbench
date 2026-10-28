package com.promenar.infinity.data.model

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.SessionEntity
import kotlinx.serialization.encodeToString
import org.junit.Test

class BranchSessionMapperTest {
    @Test
    fun `分支关系方案worktree和自动处理状态在映射与序列化中保留`() {
        val entity = SessionEntity(
            id = "branch", agentId = "agent", executionMode = "workspace", createdAt = 1, updatedAt = 2,
            parentSessionId = "parent", branchPlanJson = "{\"topic\":\"审查\"}",
            worktreeDirectory = "/workspace/branch", worktreeBranch = "feature", worktreeBase = "abc123",
            parentSessionDeleted = true, sessionAutoWakeCount = 8, sessionAutoProcessingPaused = true,
        )
        assertThat(entity.toDomain().toEntity()).isEqualTo(entity.copy(options = json.encodeToString(SessionOptions())))
        val decoded = json.decodeFromString<Session>(json.encodeToString(entity.toDomain()))
        assertThat(decoded.parentSessionId).isEqualTo("parent")
        assertThat(decoded.branchPlanJson).isEqualTo(entity.branchPlanJson)
        assertThat(decoded.worktreeDirectory).isEqualTo(entity.worktreeDirectory)
        assertThat(decoded.worktreeBranch).isEqualTo(entity.worktreeBranch)
        assertThat(decoded.worktreeBase).isEqualTo(entity.worktreeBase)
        assertThat(decoded.parentSessionDeleted).isTrue()
        assertThat(decoded.sessionAutoWakeCount).isEqualTo(8)
        assertThat(decoded.sessionAutoProcessingPaused).isTrue()
    }

    @Test
    fun `旧会话JSON默认没有分支关系或自动处理暂停`() {
        val session = json.decodeFromString<Session>("""{"id":"old","agentId":"agent"}""")
        assertThat(session.parentSessionId).isNull()
        assertThat(session.branchPlanJson).isNull()
        assertThat(session.worktreeDirectory).isNull()
        assertThat(session.worktreeBranch).isNull()
        assertThat(session.worktreeBase).isNull()
        assertThat(session.parentSessionDeleted).isFalse()
        assertThat(session.sessionAutoWakeCount).isEqualTo(0)
        assertThat(session.sessionAutoProcessingPaused).isFalse()
    }
}
