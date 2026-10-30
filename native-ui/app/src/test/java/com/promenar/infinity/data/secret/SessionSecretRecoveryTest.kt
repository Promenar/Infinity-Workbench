package com.promenar.infinity.data.secret

import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.domain.secret.SecretSlotState
import com.promenar.infinity.domain.secret.SecretSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SessionSecretRecoveryTest {
    @Test fun `从合法工具参数恢复失效标识不恢复值也不覆盖新输入`() = runBlocking {
        val calls = listOf(
            ToolCall("input", "request_secret", """{"purpose":"登录","destination":{"type":"session_env","name":"INPUT_TOKEN"}}"""),
            ToolCall("out", "shell_exec", """{"command":"generate","capture_secret":{"name":"OUTPUT_TOKEN","label":"令牌","from":"stdout"}}"""),
            ToolCall("invalid", "shell_exec", """{"capture_secret":{"name":"invalid-name"}}"""),
        )
        val vault = SessionSecretVault()
        vault.put("s", "INPUT_TOKEN", "新输入", SecretSource.UserInput, "synthetic-fresh".toByteArray())
        val results = mapOf("input" to """{"type":"saved","destination":"session_env"}""",
            "out" to """{"exit_code":0,"error_code":null,"timed_out":false,"stdout":"‹已捕获为 OUTPUT_TOKEN · 12 字节›"}""")
        SessionSecretRecovery.declaredSlots(Json.encodeToString(calls), results).forEach {
            vault.rememberUnavailable("s", it.name, it.label, it.source)
        }
        assertEquals("synthetic-fresh", vault.revealBytes("s", "INPUT_TOKEN")!!.decodeToString())
        assertNull(vault.revealBytes("s", "OUTPUT_TOKEN"))
        assertEquals(SecretSlotState.DESTROYED, vault.observeSlots("s").first().single { it.name == "OUTPUT_TOKEN" }.state)
        assertTrue(runCatching { vault.environment("s", "command \$OUTPUT_TOKEN") }.isFailure)
        assertTrue(vault.environment("s", "command \$ORDINARY_ENV").isEmpty())
    }

    @Test fun `损坏历史与无关调用不导致启动失败`() {
        assertTrue(SessionSecretRecovery.declaredSlots(null).isEmpty())
        assertTrue(SessionSecretRecovery.declaredSlots("invalid-json").isEmpty())
        val calls = listOf(ToolCall("call", "ask_user", "{}"))
        assertTrue(SessionSecretRecovery.declaredSlots(Json.encodeToString(calls)).isEmpty())
    }

    @Test fun `失败取消过期与未执行声明不会占用系统环境变量`() {
        val call = ToolCall("capture", "shell_exec", """{"command":"true","capture_secret":{"name":"PATH","label":"路径","from":"stdout"}}""")
        val encoded = Json.encodeToString(listOf(call))
        assertTrue(SessionSecretRecovery.declaredSlots(encoded).isEmpty())
        assertTrue(SessionSecretRecovery.declaredSlots(encoded, mapOf("capture" to """{"error_code":"SECRET_EMPTY","exit_code":0,"stdout":""}""")).isEmpty())
        val input = ToolCall("input", "request_secret", """{"purpose":"登录","destination":{"type":"session_env","name":"PATH"}}""")
        listOf("cancelled", "expired", "failed").forEach {
            assertTrue(SessionSecretRecovery.declaredSlots(Json.encodeToString(listOf(input)), mapOf("input" to """{"type":"$it"}""")).isEmpty())
        }
    }
}
