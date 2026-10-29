package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.trigger.*
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LinuxWakeProtocolTest {
    private fun parse(text: String) = LinuxWakeParser.parse(text.toByteArray(), Instant.EPOCH)
    private fun message(body: String, title: String = "") = buildJsonObject {
        put("v", 1); put("key", "build"); put("title", title); put("body", body)
        put("created_at", "2026-10-29T00:00:00Z")
    }.toString()

    @Test fun `完整消息与缺省字段`() {
        val result = parse(message("中文\n正文", "构建")) as LinuxWakeParseResult.Success
        assertEquals(LinuxWakeMessage("build", "构建", "中文\n正文", Instant.parse("2026-10-29T00:00:00Z")), result.message)
        val defaults = (parse("{\"v\":1}") as LinuxWakeParseResult.Success).message
        assertEquals("default", defaults.key)
        assertEquals("", defaults.title)
        assertEquals("", defaults.body)
        assertEquals(Instant.EPOCH, defaults.createdAt)
        assertEquals(Instant.EPOCH, (parse("{\"v\":1,\"created_at\":\"坏时间\",\"body\":null}") as LinuxWakeParseResult.Success).message.createdAt)
    }

    @Test fun `拒绝格式版本键类型及大小异常`() {
        for (text in listOf("[1]", "{", "{\"v\":2}", "{\"v\":\"1\"}", "{}", "{\"v\":1,\"key\":\"\"}",
            "{\"v\":1,\"key\":\"a/b\"}", "{\"v\":1,\"key\":\"${"k".repeat(65)}\"}", "{\"v\":1,\"key\":null}", "{\"v\":1,\"body\":123}")) {
            assertTrue(parse(text) is LinuxWakeParseResult.Failure, text)
        }
        assertTrue(LinuxWakeParser.parse(ByteArray(LinuxWakeLimits.MAX_FILE_BYTES + 1)) is LinuxWakeParseResult.Failure)
        assertTrue(LinuxWakeParser.parse(byteArrayOf(0xc3.toByte(), 0x28)) is LinuxWakeParseResult.Failure)
        assertTrue(parse("{\"v\":1,\"key\":\"A.z_-9\"}") is LinuxWakeParseResult.Success)
    }

    @Test fun `正文按UTF16限制且不会切开代理对并追加标记`() {
        val body = (parse(message("a".repeat(5000))) as LinuxWakeParseResult.Success).message.body
        assertEquals(LinuxWakeLimits.MAX_BODY_CHARS, body.length)
        assertTrue(body.endsWith(LinuxWakeLimits.TRUNCATED_MARKER))
        val emoji = (parse(message("😀".repeat(3000))) as LinuxWakeParseResult.Success).message.body
        assertTrue(emoji.length <= LinuxWakeLimits.MAX_BODY_CHARS)
        assertFalse(emoji.removeSuffix(LinuxWakeLimits.TRUNCATED_MARKER).last().isHighSurrogate())
        assertEquals("a".repeat(4096), (parse(message("a".repeat(4096))) as LinuxWakeParseResult.Success).message.body)
    }

    @Test fun `标题去换行限制120且空标题保留`() {
        val title = (parse(message("", "甲\r\n乙" + "字".repeat(200))) as LinuxWakeParseResult.Success).message.title
        assertEquals(120, title.length)
        assertFalse(title.contains('\n')); assertFalse(title.contains('\r'))
        val request = LinuxWakeRequests.compose("s", listOf((parse("{\"v\":1}") as LinuxWakeParseResult.Success).message))
        assertEquals("", request.title)
        assertEquals("wake:default", request.sourceId)
        assertEquals(SystemTurnKind.SCRIPT_WAKE, request.kind)
        assertTrue(request.body.contains("不是用户亲自输入"))
    }

    @Test fun `脚本唤醒来源和标记正文往返`() {
        assertEquals(SystemTurnKind.SCRIPT_WAKE, SystemTurnKind.fromWire("script_wake"))
        val request = SystemTurnRequest("s", SystemTurnKind.SCRIPT_WAKE, "wake:build", "", "构建\n完成")
        val parsed = SystemTriggerMessage.parse(SystemTriggerMessage.compose(request, OffsetDateTime.parse("2026-10-29T00:00:00Z")))!!
        assertEquals(request.kind, parsed.kind); assertEquals(request.sourceId, parsed.sourceId)
        assertEquals(request.title, parsed.title); assertEquals(request.body, parsed.body)
    }
}
