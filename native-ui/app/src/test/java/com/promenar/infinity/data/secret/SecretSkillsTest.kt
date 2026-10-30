package com.promenar.infinity.data.secret

import com.promenar.infinity.domain.secret.SecretIoLimits
import com.promenar.infinity.domain.secret.SecretSource
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.tool.ToolSchemaValidation
import com.promenar.infinity.domain.tool.ToolSchemaValidator
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.RequestSecretSkill
import com.promenar.infinity.ui.chat.manager.skills.SecretRequestProtocol
import com.promenar.infinity.ui.chat.manager.skills.ShowSecretSkill
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SecretSkillsTest {
    private val vault = SessionSecretVault()
    private val context = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
        override val assistantMessageId = "assistant"
        override val toolStepId = "step_123_call_id_with_underscores"
    }

    @Test fun `两个工具schema有效且无需命令审批`() {
        listOf(RequestSecretSkill(), ShowSecretSkill(vault)).forEach {
            assertTrue(ToolSchemaValidator().validateDefinition(it.parametersSchema) is ToolSchemaValidation.Valid)
            assertEquals(ToolRisk.SAFE_READ, it.risk)
        }
    }

    @Test fun `Git去向与严格多态参数匹配`() {
        val valid = """{"purpose":"用于登录","destination":{"type":"git_credential","host":"https://GitHub.com/"}}"""
        assertTrue(SecretRequestProtocol.decode(valid).toString().contains("github.com"))
        listOf(valid.replace("用于登录", "x".repeat(SecretIoLimits.MAX_PURPOSE_CHARS + 1)),
            valid.replace("\"host\":", "\"name\":\"OTHER\",\"host\":"), valid.replace("git_credential", "invalid"))
            .forEach { assertTrue(runCatching { SecretRequestProtocol.decode(it) }.isFailure) }
    }

    @Test fun `show只返回状态并绑定原toolCallId且销毁后返回固定错误`() = runBlocking {
        val args = Json.parseToJsonElement("""{"name":"TEST_TOKEN","purpose":"交付用户"}""").jsonObject
        val skill = ShowSecretSkill(vault)
        assertTrue(skill.execute(args, context).content.contains("SECRET_SLOT_NOT_FOUND"))
        val longPurpose = Json.parseToJsonElement("""{"name":"TEST_TOKEN","purpose":"${"😀".repeat(101)}"}""").jsonObject
        assertTrue(skill.execute(longPurpose, context).content.contains("SECRET_INVALID_ARGUMENTS"))
        vault.put("s", "TEST_TOKEN", "令牌", SecretSource.UserInput, "synthetic-output".toByteArray())
        val result = skill.execute(args, context)
        assertEquals("success", result.status)
        assertTrue(result.content.contains("shown"))
        assertFalse(result.content.contains("synthetic-output"))
        val card = vault.observeCards("s").first().single()
        assertEquals("assistant", card.assistantMessageId)
        assertEquals("call_id_with_underscores", card.toolCallId)
        vault.destroy("s", "TEST_TOKEN")
        assertTrue(skill.execute(args, context).content.contains("SECRET_SLOT_NOT_FOUND"))
    }
}
