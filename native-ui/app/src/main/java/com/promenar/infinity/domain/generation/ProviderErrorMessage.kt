package com.promenar.infinity.domain.generation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 服务商错误响应里可给用户看的原因。
 *
 * 只取响应体中的错误说明字段（或简短纯文本），去掉控制字符、遮蔽疑似密钥后截断；
 * HTML 错误页与无法识别的长文本不采用。结果作为 [GenerationFailure.KEY_PROVIDER_MESSAGE] 随失败持久化。
 */
object ProviderErrorMessage {
    const val MAX_LENGTH = 200
    private const val MAX_PLAIN_BODY = 400
    private val json = Json { isLenient = true }
    private val keyLike = listOf(
        Regex("""sk-[A-Za-z0-9_\-]{6,}""") to "sk-***",
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=\-]+""") to "Bearer ***",
        Regex("""(?i)\b(api[_-]?key|access[_-]?token|token|key)\s*[=:]\s*[^\s&,;"']+""") to "$1=***",
        Regex("""[A-Za-z0-9_\-]{40,}""") to "***",
    )

    fun extract(body: String?): String? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty() || text.startsWith("<")) return null
        val candidate = if (text.startsWith("{") || text.startsWith("[")) {
            runCatching { fromJson(json.parseToJsonElement(text)) }.getOrNull()
        } else {
            text.takeIf { it.length <= MAX_PLAIN_BODY }
        }
        return candidate?.let(::sanitize)
    }

    /** 持久化与展示前的统一清洗；返回 null 表示没有可展示内容。 */
    fun sanitize(raw: String): String? {
        var text = raw.map { if (Character.isISOControl(it)) ' ' else it }.joinToString("")
        keyLike.forEach { (pattern, replacement) -> text = pattern.replace(text, replacement) }
        text = text.replace(Regex("""\s+"""), " ").trim()
        if (text.isEmpty()) return null
        if (text.length <= MAX_LENGTH) return text
        val cut = text.take(MAX_LENGTH - 1).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
        return "$cut…"
    }

    private fun fromJson(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> {
            val error = element["error"]
            val nested = (error as? JsonObject)?.let { fromObject(it) }
            nested ?: (error as? JsonPrimitive)?.contentOrNull?.takeIf { error.isString } ?: fromObject(element)
        }
        else -> (element as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let(::fromJson)
    }

    private fun fromObject(obj: JsonObject): String? =
        listOf("message", "msg", "detail", "error_msg", "errorMessage")
            .firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank) }
            ?: (obj["errors"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let(::fromJson)
}
