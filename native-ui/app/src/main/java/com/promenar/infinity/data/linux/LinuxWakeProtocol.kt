package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.trigger.SystemTurnKind
import com.promenar.infinity.domain.trigger.SystemTurnRequest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import kotlinx.serialization.json.*

object LinuxWakeLimits {
    const val GUEST_DIRECTORY = "/run/infinity/wake"
    const val ENVIRONMENT_VARIABLE = "INFINITY_WAKE_DIR"
    const val HOST_DIRECTORY = "linux_wake_inbox"
    const val SCRIPT_PATH = "usr/local/bin/infinity-wake"
    const val DEFAULT_KEY = "default"
    const val MAX_KEY_CHARS = 64
    const val MAX_TITLE_CHARS = 120
    const val MAX_FILE_BYTES = 64 * 1024
    const val MAX_BODY_CHARS = 4096
    const val MAX_INBOX_FILES = 32
    const val MAX_PENDING_KEYS = 8
    const val MAX_MERGED_BODY_CHARS = 16384
    const val MIN_DISPATCH_INTERVAL = 60_000L
    const val TEMP_MAX_AGE = 10 * 60_000L
    const val TRUNCATED_MARKER = "…[已截断]"
    val KEY_PATTERN = Regex("[A-Za-z0-9._-]{1,$MAX_KEY_CHARS}")
}

data class LinuxWakeMessage(val key: String, val title: String, val body: String, val createdAt: Instant)

sealed interface LinuxWakeParseResult {
    data class Success(val message: LinuxWakeMessage) : LinuxWakeParseResult
    data class Failure(val reason: String) : LinuxWakeParseResult
}

object LinuxWakeParser {
    fun parse(bytes: ByteArray, fallbackTime: Instant = Instant.now()): LinuxWakeParseResult {
        if (bytes.size > LinuxWakeLimits.MAX_FILE_BYTES) return LinuxWakeParseResult.Failure("文件超过大小限制")
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val json = Json.parseToJsonElement(text) as? JsonObject ?: error("需要 JSON 对象")
            val version = json["v"] as? JsonPrimitive
            require(version != null && !version.isString && version.content == "1")
            fun string(name: String, default: String = "", nullable: Boolean = false): String {
                val element = json[name] ?: return default
                if (nullable && element == JsonNull) return default
                require(element is JsonPrimitive && element.isString)
                return element.content
            }
            val key = string("key", LinuxWakeLimits.DEFAULT_KEY)
            require(LinuxWakeLimits.KEY_PATTERN.matches(key))
            val title = takeUtf16(string("title").replace('\r', ' ').replace('\n', ' '), LinuxWakeLimits.MAX_TITLE_CHARS)
            val original = string("body", nullable = true)
            val body = if (original.length <= LinuxWakeLimits.MAX_BODY_CHARS) original else
                takeUtf16(original, LinuxWakeLimits.MAX_BODY_CHARS - LinuxWakeLimits.TRUNCATED_MARKER.length) + LinuxWakeLimits.TRUNCATED_MARKER
            val createdAt = runCatching { Instant.parse(string("created_at")) }.getOrDefault(fallbackTime)
            LinuxWakeParseResult.Success(LinuxWakeMessage(key, title, body, createdAt))
        } catch (_: Exception) {
            // 不将异常消息或原始 JSON 写入日志，避免泄露脚本正文。
            LinuxWakeParseResult.Failure("JSON、版本或字段无效")
        }
    }

    private fun takeUtf16(text: String, limit: Int): String {
        var end = minOf(text.length, limit)
        if (end < text.length && end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return text.substring(0, end)
    }
}

object LinuxWakeRequests {
    /** 未指定 key 时使用 default；该 key 在本会话共用一个可被较新消息替换的待发来源。 */
    fun compose(sessionId: String, messages: List<LinuxWakeMessage>): SystemTurnRequest {
        require(messages.isNotEmpty())
        val latest = messages.last()
        val header = "以下消息由本会话 Linux 环境中的脚本通过 infinity-wake 发送，不是用户亲自输入。"
        fun entry(message: LinuxWakeMessage) = "key：${message.key}；标题：${message.title}；投递时间：${message.createdAt}\n${message.body}"
        val ordered = messages.sortedBy { it.createdAt }.toMutableList()
        var omitted = 0
        fun body() = buildString {
            appendLine(header)
            if (omitted > 0) appendLine("[已省略最旧的 $omitted 条消息]")
            append(ordered.joinToString("\n\n", transform = ::entry))
        }
        var body = body()
        while (body.length > LinuxWakeLimits.MAX_MERGED_BODY_CHARS && ordered.size > 1) {
            ordered.removeAt(0); omitted++; body = body()
        }
        return SystemTurnRequest(sessionId, SystemTurnKind.SCRIPT_WAKE, "wake:${latest.key}", latest.title, body)
    }
}
