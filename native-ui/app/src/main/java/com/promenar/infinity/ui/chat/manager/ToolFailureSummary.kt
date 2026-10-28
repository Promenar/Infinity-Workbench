package com.promenar.infinity.ui.chat.manager

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 工具失败时写入执行步骤与工具消息的同一份摘要。
 * 结构化结果先保留根因字段（错误码、指引、退出码），输出保留尾部并标注原始长度，总长有界；
 * 纯文本保留开头。
 */
internal object ToolFailureSummary {
    const val PREFIX = "工具执行失败："
    const val MAX_CHARS = 4_000
    const val PLAIN_MAX_CHARS = 1_000

    private val ROOT_CAUSE_KEYS = listOf("error_code", "guidance", "exit_code", "timed_out", "err", "errmsg")
    private val OUTPUT_KEYS = listOf("stderr", "stdout")

    /** 超长时依次压缩：stdout、stderr 保留尾部，errmsg 保留开头；error_code、guidance、exit_code 不压缩。 */
    private val COMPRESSIBLE = listOf("stdout" to true, "stderr" to true, "errmsg" to false)

    fun format(content: String): String = PREFIX + summarize(content)

    private fun summarize(content: String): String {
        val source = runCatching { Json.parseToJsonElement(content) }.getOrNull() as? JsonObject
        if (source == null || source.keys.none { it in ROOT_CAUSE_KEYS || it in OUTPUT_KEYS }) {
            return keepHead(content, PLAIN_MAX_CHARS)
        }
        val fields = LinkedHashMap<String, JsonElement>()
        ROOT_CAUSE_KEYS.forEach { key -> source[key]?.takeUnless { it is JsonNull }?.let { fields[key] = it } }
        source.forEach { (key, value) ->
            if (key !in ROOT_CAUSE_KEYS && key !in OUTPUT_KEYS && value is JsonPrimitive && value !is JsonNull) fields[key] = value
        }
        OUTPUT_KEYS.forEach { key -> source.string(key)?.takeIf(String::isNotEmpty)?.let { fields[key] = JsonPrimitive(it) } }

        var encoded = JsonObject(fields).toString()
        for ((key, keepTail) in COMPRESSIBLE) {
            if (encoded.length <= MAX_CHARS) break
            val text = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val original = maxOf(text.length.toLong(), source.long("${key}_original_length") ?: 0L)
            fields[key] = JsonPrimitive(marker(original))
            // 预算按编码后长度计算：换行、引号等转义后占多个字符。
            val budget = MAX_CHARS - JsonObject(fields).toString().length
            var keep = budget
            var kept = ""
            while (keep > 0) {
                kept = if (keepTail) takeLastSafe(text, keep) else takeSafe(text, keep)
                val excess = encodedLength(kept) - budget
                if (excess <= 0) break
                keep -= excess
                kept = ""
            }
            fields[key] = JsonPrimitive(if (keepTail) marker(original) + kept else kept + marker(original))
            encoded = JsonObject(fields).toString()
        }
        return encoded
    }

    private fun keepHead(text: String, limit: Int): String =
        if (text.length <= limit) text else takeSafe(text, limit) + marker(text.length.toLong())

    /** 字符串编码进 JSON 后增加的长度（不含两侧引号）。 */
    private fun encodedLength(text: String) = JsonPrimitive(text).toString().length - 2

    private fun marker(original: Long) ="…(已截断，原始 $original 字符)"

    private fun takeSafe(text: String, count: Int): String {
        var end = count.coerceIn(0, text.length)
        if (end in 1 until text.length && text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end)
    }

    private fun takeLastSafe(text: String, count: Int): String {
        var start = (text.length - count).coerceIn(0, text.length)
        if (start in 1 until text.length && text[start].isLowSurrogate()) start++
        return text.substring(start)
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
}
