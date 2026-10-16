package com.promenar.nexara.domain.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URI

/**
 * 按调用参数追加的审批规则：工具本身风险低，但某些参数可能把会话数据带到外部。
 * 外传参数在半自动模式审批；不可恢复删除在全部模式下逐次审批。
 */
object ToolArgumentApprovalRules {
    fun requiresExplicitApproval(toolName: String, arguments: String): Boolean = toolName == "delete_file" &&
        runCatching {
            ((Json.parseToJsonElement(arguments) as? JsonObject)?.get("irreversible") as? JsonPrimitive)?.booleanOrNull == true
        }.getOrDefault(false)
    private const val WEB_FETCH = "web_fetch"
    private const val MAX_URL_LENGTH = 300
    private const val MAX_PATH_SEGMENT_LENGTH = 80
    private val encodedSegment = Regex("^[A-Za-z0-9+/=_%.]{24,}$")
    private val json = Json { ignoreUnknownKeys = true }

    fun requiresApprovalInSemi(toolName: String, arguments: String): Boolean = when (toolName) {
        WEB_FETCH -> webFetchMayCarryData(arguments)
        else -> false
    }

    /**
     * 网址带查询参数、用户信息、片段之外的超长内容或形似编码数据的路径段时，
     * 视为可能携带会话数据，需要用户确认。解析失败同样要求确认。
     */
    internal fun webFetchMayCarryData(arguments: String): Boolean {
        val raw = runCatching {
            ((json.parseToJsonElement(arguments) as? JsonObject)?.get("url") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.trim()
        }.getOrNull()
        if (raw.isNullOrEmpty()) return true
        val normalized = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return true
        if (uri.host.isNullOrBlank()) return true
        if (!uri.rawQuery.isNullOrEmpty()) return true
        if (!uri.rawUserInfo.isNullOrEmpty()) return true
        val withoutFragment = normalized.substringBefore('#')
        if (withoutFragment.length > MAX_URL_LENGTH) return true
        val segments = uri.rawPath.orEmpty().split('/').filter(String::isNotEmpty)
        return segments.any { segment ->
            segment.length > MAX_PATH_SEGMENT_LENGTH || looksEncoded(segment)
        }
    }

    /** 无连字符分词、同时含字母与数字的长段落，例如 base64、十六进制或 URL 编码数据。 */
    private fun looksEncoded(segment: String): Boolean =
        encodedSegment.matches(segment) &&
            segment.any(Char::isDigit) &&
            segment.any(Char::isLetter)
}
