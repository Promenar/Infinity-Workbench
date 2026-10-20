package com.promenar.nexara.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 服务端错误体中可读的部分：错误码与说明。 */
internal data class ApiErrorBody(val code: String?, val message: String?)

/** HTTP 失败的可读摘要：`HTTP 503 · model_not_found` 与服务端说明。 */
internal data class HttpErrorSummary(val head: String, val detail: String?)

internal const val HTTP_ERROR_DETAIL_MAX_CHARS = 500

/**
 * 解析 OpenAI 兼容网关常见的错误体：`{"error":{"code","message","type"}}`、`{"error":"..."}`、
 * 顶层 `message`/`detail`。上游已截断的 JSON 仍按字段名找回错误码与说明；不是错误体时返回 null。
 */
internal fun parseApiErrorBody(body: String): ApiErrorBody? {
    val text = body.trim()
    if (text.isEmpty()) return null
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()
    if (root != null) {
        val obj = root as? JsonObject ?: return null
        val error = obj["error"]
        val nested = error as? JsonObject
        val code = (nested?.get("code") ?: nested?.get("type") ?: obj["code"]).text()
        val message = (nested?.get("message") ?: error.takeIf { it is JsonPrimitive } ?: obj["message"] ?: obj["detail"]).text()
        return if (code == null && message == null) null else ApiErrorBody(code, message)
    }
    if (!text.startsWith("{")) return null
    val code = TRUNCATED_CODE.find(text)?.groupValues?.get(1)?.let(::unescape)
    val message = TRUNCATED_MESSAGE.find(text)?.groupValues?.get(1)?.let(::unescape)
    return if (code == null && message == null) null else ApiErrorBody(code, message)
}

/** 状态码、错误码与说明组成的摘要；说明过长时保留开头。 */
internal fun httpErrorSummary(status: Int, body: String): HttpErrorSummary {
    val parsed = parseApiErrorBody(body)
    val head = listOfNotNull("HTTP $status", parsed?.code).joinToString(" · ")
    val detail = (parsed?.message ?: body.trim()).takeIf(String::isNotBlank)?.take(HTTP_ERROR_DETAIL_MAX_CHARS)
    return HttpErrorSummary(head, detail)
}

private val TRUNCATED_CODE = Regex(""""code"\s*:\s*"((?:[^"\\]|\\.)*)"""")
private val TRUNCATED_MESSAGE = Regex(""""message"\s*:\s*"((?:[^"\\]|\\.)*)""")

private fun JsonElement?.text(): String? =
    (this as? JsonPrimitive)?.takeUnless { it.content == "null" && !it.isString }?.content?.takeIf(String::isNotBlank)

private fun unescape(value: String): String =
    runCatching { Json.parseToJsonElement("\"$value\"") as JsonPrimitive }.getOrNull()?.content ?: value
