package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class CurrentTimeSkillTest {

    private val skill = CurrentTimeSkill()
    private val ctx = object : SkillExecutionContext {
        override val sessionId = "test-session"
        override val agentId = "test-agent"
        override val workspacePath = null
    }

    @Test
    fun `returns success status`() = runTest {
        val result = skill.execute(emptyMap(), ctx)
        assertThat(result.status).isEqualTo("success")
    }

    @Test
    fun `returns valid ISO-8601 datetime`() = runTest {
        val result = skill.execute(emptyMap(), ctx)
        // 2026-05-14T03:17:00.123+08:00
        val parsed = ZonedDateTime.parse(result.content, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        assertThat(parsed).isNotNull()
    }

    @Test
    fun `returns time close to current moment`() = runTest {
        val before = ZonedDateTime.now()
        val result = skill.execute(emptyMap(), ctx)
        val after = ZonedDateTime.now()

        val parsed = ZonedDateTime.parse(result.content, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        // 执行应当在 before 和 after 之间（允许 2s 误差）
        assertThat(parsed.isAfter(before.minusSeconds(2))).isTrue()
        assertThat(parsed.isBefore(after.plusSeconds(2))).isTrue()
    }

    @Test
    fun `content contains timezone offset`() = runTest {
        val result = skill.execute(emptyMap(), ctx)
        // ISO_OFFSET_DATE_TIME 应包含时区偏移（如 +08:00 或 Z）
        assertThat(result.content).containsMatch("\\+\\d{2}:\\d{2}|\\-\\d{2}:\\d{2}|Z")
    }

    @Test
    fun `result id is unique`() = runTest {
        val r1 = skill.execute(emptyMap(), ctx)
        val r2 = skill.execute(emptyMap(), ctx)
        assertThat(r1.id).isNotEqualTo(r2.id)
    }
}
