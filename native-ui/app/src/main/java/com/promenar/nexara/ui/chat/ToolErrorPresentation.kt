package com.promenar.nexara.ui.chat

import com.promenar.nexara.data.remote.parseApiErrorBody
import com.promenar.nexara.ui.chat.manager.ToolFailureSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** 工具失败在步骤详情中的展示：错误码类标签、可读说明与命令输出等细节。 */
internal data class ToolErrorPresentation(
    val tags: List<String>,
    val message: String,
    val details: String?,
)

private val MESSAGE_KEYS = listOf("guidance", "errmsg", "err", "message", "error")
private val DETAIL_KEYS = listOf("stderr", "stdout")
private val HTTP_STATUS = Regex("""(?:HTTP\s*|\()([1-5]\d{2})\b""")
/** [com.promenar.nexara.data.remote.httpErrorSummary] 生成的 `(HTTP 503 · code):` 段。 */
private val HTTP_SUMMARY = Regex("""\(HTTP ([1-5]\d{2})(?: · ([^)]+))?\):?""")

/**
 * 从工具消息还原展示结构。工具消息以 [ToolFailureSummary.PREFIX] 开头供模型阅读，界面由标题表达失败，去掉该前缀；
 * 结构化结果取错误码、退出码与超时作标签；纯文本中嵌入的服务端错误体（含被截断的 JSON）提取状态码、错误码与说明。
 */
internal fun toolErrorPresentation(content: String): ToolErrorPresentation {
    val text = content.removePrefix(ToolFailureSummary.PREFIX).trim()
    (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.let { structured ->
        val tags = buildList {
            structured.string("error_code")?.let(::add)
            (structured["exit_code"] as? JsonPrimitive)?.longOrNull?.let { add("exit $it") }
            if ((structured["timed_out"] as? JsonPrimitive)?.booleanOrNull == true) add("timed_out")
        }
        val message = MESSAGE_KEYS.firstNotNullOfOrNull(structured::string)
        val details = DETAIL_KEYS.mapNotNull(structured::string).joinToString("\n").ifEmpty { null }
        if (tags.isNotEmpty() || message != null || details != null) return ToolErrorPresentation(tags, message.orEmpty(), details)
    }
    HTTP_SUMMARY.find(text)?.let { match ->
        val tags = listOfNotNull("HTTP ${match.groupValues[1]}", match.groupValues[2].takeIf(String::isNotBlank))
        val rest = text.substring(match.range.last + 1).trim()
        return ToolErrorPresentation(tags, rest.ifEmpty { text.substring(0, match.range.first).trim() }, null)
    }
    val brace = text.indexOf('{')
    if (brace >= 0) {
        val api = parseApiErrorBody(text.substring(brace))
        if (api?.message != null) {
            val status = HTTP_STATUS.find(text.substring(0, brace))?.groupValues?.get(1)
            return ToolErrorPresentation(listOfNotNull(status?.let { "HTTP $it" }, api.code), api.message, null)
        }
    }
    return ToolErrorPresentation(emptyList(), text, null)
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
