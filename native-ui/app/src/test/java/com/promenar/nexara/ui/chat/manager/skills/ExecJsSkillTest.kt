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
        override val workspaceRootUuid = "root-1"
    }

    @BeforeEach
    fun setUp() {
        mockContext = mockk(relaxed = true)
        every { mockContext.applicationContext } returns mockContext
        skill = ExecJsSkill(mockContext)
    }

    @Test
    fun `returns error for missing code parameter`() = runTest {
        val result = skill.execute(skillArgs(), testExecContext)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("Missing required parameter: code")
    }

    @Test
    fun `returns error for code too long`() = runTest {
        val longCode = "a".repeat(50001)
        val result = skill.execute(skillArgs("code" to longCode), testExecContext)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("Code too long")
    }

    @Test
    fun `accepts code at max length boundary`() = runTest {
        val maxCode = "a".repeat(50000)
        val result = skill.execute(skillArgs("code" to maxCode), testExecContext)
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

    @Test
    fun `decodes WebView JSON string results with escapes and unicode`() = runTest {
        // WebView 对字符串返回值再做一层 JSON 编码，内部包含反斜杠与 Unicode 转义。
        val inner = """{"ok":true,"value":"C:\\tmp \u4e2d \"q\""}"""
        val encoded = kotlinx.serialization.json.JsonPrimitive(inner).toString()
        val evaluating = ExecJsSkill(mockContext, evaluator = { encoded })

        val result = evaluating.execute(skillArgs("code" to "result = 1"), testExecContext)

        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains("中")
    }

    @Test
    fun `surfaces script errors from decoded result`() = runTest {
        val encoded = kotlinx.serialization.json.JsonPrimitive("""{"ok":false,"error":"ReferenceError: x"}""").toString()
        val evaluating = ExecJsSkill(mockContext, evaluator = { encoded })

        val result = evaluating.execute(skillArgs("code" to "x"), testExecContext)

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("ReferenceError")
    }


}
