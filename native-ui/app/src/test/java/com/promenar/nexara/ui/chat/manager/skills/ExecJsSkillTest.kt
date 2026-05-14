package com.promenar.nexara.ui.chat.manager.skills

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExecJsSkillTest {

    private lateinit var mockContext: Context
    private lateinit var skill: ExecJsSkill
    private val testExecContext = object : SkillExecutionContext {
        override val sessionId = "s1"
        override val agentId = "a1"
        override val workspacePath: String? = null
    }

    @BeforeEach
    fun setUp() {
        mockContext = mockk(relaxed = true)
        every { mockContext.applicationContext } returns mockContext
        skill = ExecJsSkill(mockContext)
    }

    @Test
    fun `returns error for missing code parameter`() = runTest {
        val result = skill.execute(emptyMap(), testExecContext)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("Missing required parameter: code")
    }

    @Test
    fun `returns error for code too long`() = runTest {
        val longCode = "a".repeat(50001)
        val result = skill.execute(mapOf("code" to longCode), testExecContext)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("Code too long")
    }

    @Test
    fun `accepts code at max length boundary`() = runTest {
        val maxCode = "a".repeat(50000)
        val result = skill.execute(mapOf("code" to maxCode), testExecContext)
        assertThat(result.status).isNotEqualTo("Missing required parameter: code")
    }

    @Test
    fun `id is exec_js`() {
        assertThat(skill.id).isEqualTo("exec_js")
    }

    @Test
    fun `parametersSchema requires code`() {
        assertThat(skill.parametersSchema).contains("\"required\":[\"code\"]")
    }
}
