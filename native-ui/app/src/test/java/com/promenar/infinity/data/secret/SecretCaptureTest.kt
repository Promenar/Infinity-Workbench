package com.promenar.infinity.data.secret

import com.promenar.infinity.domain.secret.SecretIoLimits
import com.promenar.infinity.domain.tool.CommandToolResult
import com.promenar.infinity.domain.tool.ToolSchemaValidation
import com.promenar.infinity.domain.tool.ToolSchemaValidator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SecretCaptureTest {
    private val vault = SessionSecretVault()
    private val capture = SecretCapture("TOKEN", "令牌", "stdout", null)

    @Test fun `捕获schema能通过应用现有工具门禁`() {
        val schema = """{"type":"object","properties":{${SecretCapture.schema}}}"""
        assertTrue(ToolSchemaValidator().validateDefinition(schema) is ToolSchemaValidation.Valid)
    }

    @Test fun `参数缺失不捕获但非法对象立即报固定码`() {
        assertNull(SecretCapture.parse(Json.parseToJsonElement("{}").jsonObject))
        for (json in listOf("null", "42", "{}", "{\"name\":\"bad-name\",\"label\":\"x\",\"from\":\"stdout\"}", "{\"name\":\"TOKEN\",\"label\":\"x\",\"from\":\"file\"}")) {
            val args = Json.parseToJsonElement("{\"capture_secret\":$json}").jsonObject
            assertEquals("SECRET_CAPTURE_INVALID", assertThrows(IllegalArgumentException::class.java) { SecretCapture.parse(args) }.message)
        }
        assertEquals(capture, SecretCapture.parse(Json.parseToJsonElement("{\"capture_secret\":{\"name\":\"TOKEN\",\"label\":\"令牌\",\"from\":\"stdout\"}}").jsonObject))
        assertNotNull(Json.parseToJsonElement("{${SecretCapture.schema}}").jsonObject["capture_secret"])
    }

    @Test fun `完整stdout只去末尾换行并遮蔽stderr`() = runBlocking {
        val result = capture.complete("s", vault, CommandToolResult(exitCode = 0, stdout = " test-token \r\n\n", stderr = "warning  test-token ")) { error("不读文件") }
        assertEquals(" test-token ", vault.revealBytes("s", "TOKEN")!!.decodeToString())
        assertEquals("‹已捕获为 TOKEN · 12 字节›", result.stdout)
        assertFalse(result.toJson().toString().contains("test-token"))
        assertFalse(result.stdoutTruncated)
    }

    @Test fun `失败命令不捕获也不把stdout或stderr中的值交回`() = runBlocking {
        val result = capture.complete("s", vault, CommandToolResult(exitCode = 2, stdout = "test-token\n", stderr = "bad test-token")) { error("不读文件") }
        assertNull(vault.revealBytes("s", "TOKEN"))
        assertEquals("", result.stdout)
        assertFalse(result.toJson().toString().contains("test-token"))
        assertEquals("SECRET_CAPTURE_COMMAND_FAILED", result.errorCode)
    }

    @Test fun `捕获失败只交回状态避免诊断中的未知敏感子串`() = runBlocking {
        val result = capture.complete("s", vault, CommandToolResult(exitCode = 2, stdout = " prefix-test-token-suffix \n", stderr = "bad test-token")) { error("不读文件") }
        assertFalse(result.toJson().toString().contains("test-token"))
        assertEquals("", result.stderr)
    }

    @Test fun `截断输出和超限输出不会被错误当成完整秘密`() = runBlocking {
        val incomplete = capture.complete("s", vault, CommandToolResult(exitCode = 0, stdout = "part", stdoutTruncated = true)) { byteArrayOf() }
        assertEquals("SECRET_CAPTURE_INCOMPLETE", incomplete.errorCode)
        assertEquals("", incomplete.stdout)
        val large = capture.complete("s", vault, CommandToolResult(exitCode = 0, stdout = "x".repeat(SecretIoLimits.MAX_SECRET_BYTES + 1))) { byteArrayOf() }
        assertEquals("SECRET_TOO_LARGE", large.errorCode)
        assertEquals("", large.stdout)
        assertNull(vault.revealBytes("s", "TOKEN"))
    }

    @Test fun `stdout捕获成功仍保留stderr自身的截断事实`() = runBlocking {
        val result = capture.complete("s", vault, CommandToolResult(exitCode = 0, stdout = "test-token", stderr = "tail", stderrOriginalLength = 100, stderrTruncated = true)) { byteArrayOf() }
        assertNull(result.errorCode)
        assertTrue(result.stderrTruncated)
        assertEquals(100L, result.stderrOriginalLength)
    }

    @Test fun `file捕获保存完整字节清零读取数组且提示文件保留`() = runBlocking {
        val bytes = byteArrayOf(1, 0, 2, 3)
        val file = SecretCapture("TOKEN", "文件", "file", "relative/token")
        val result = file.complete("s", vault, CommandToolResult(exitCode = 0, stdout = "not the file")) { path ->
            assertEquals("relative/token", path)
            bytes
        }
        assertArrayEquals(byteArrayOf(1, 0, 2, 3), vault.revealBytes("s", "TOKEN"))
        assertTrue(bytes.all { it == 0.toByte() })
        assertFalse(result.stdout.contains("not the file"))
        assertTrue(result.guidance.orEmpty().contains("文件"))
    }

    @Test fun `文件读取异常不把异常消息带入结果`() = runBlocking {
        val file = SecretCapture("TOKEN", "文件", "file", "relative/token")
        val result = file.complete("s", vault, CommandToolResult(exitCode = 0)) { throw IllegalStateException("synthetic-sensitive-error") }
        assertEquals("SECRET_CAPTURE_READ_FAILED", result.errorCode)
        assertFalse(result.toJson().toString().contains("synthetic-sensitive-error"))
    }
}
