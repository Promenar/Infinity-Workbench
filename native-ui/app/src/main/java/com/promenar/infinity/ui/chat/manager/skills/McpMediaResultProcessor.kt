package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.media.*
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** 保留结果信封和内容顺序，只把二进制内容块替换为工作区引用说明。 */
class McpMediaResultProcessor(
    private val store: WorkspaceMediaStore?,
    private val maxBytes: Long = MAX_WORKSPACE_MEDIA_BYTES,
) {
    suspend fun process(result: JsonElement, sessionId: String, resultId: String = "mcp_${System.currentTimeMillis()}"): ToolResult = withContext(Dispatchers.IO) {
        val root = result as? JsonObject ?: return@withContext ToolResult(resultId, result.toString())
        val isError = (root["isError"] as? JsonPrimitive)?.booleanOrNull == true
        val content = root["content"] as? JsonArray
        if (isError || content == null || content.none(::isMedia)) {
            return@withContext ToolResult(resultId, result.toString(), status = if (isError) "error" else "success")
        }
        val saved = mutableListOf<MediaRef>()
        val replacements = mutableMapOf<String, String>()
        val replaced = content.map { element ->
            if (!isMedia(element)) return@map element
            val item = element.jsonObject
            val resource = item["resource"] as? JsonObject
            val payload = if (item.string("type") == "resource") resource?.string("blob") else item.string("data")
            val description = try {
                val bytes = decodeMediaBase64(payload, maxBytes)
                val target = store ?: throw WorkspaceMediaException("WORKSPACE_UNAVAILABLE", "当前会话工作区不可用，媒体未保存")
                val ref = target.save(sessionId, MediaPayload.Bytes(bytes), MediaOrigin.MCP,
                    resource?.string("mimeType") ?: item.string("mimeType"),
                    resource?.string("uri")?.let(::resourceName))
                saved += ref
                mediaSavedDescription(ref)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "媒体未保存：${mediaFailureDescription(failure)}"
            }
            payload?.let { replacements.putIfAbsent(it, description) }
            buildJsonObject {
                put("type", "text")
                put("text", resource?.string("text")?.let { "$it\n$description" } ?: description)
                item["annotations"]?.let { put("annotations", it) }
                item["_meta"]?.let { put("_meta", it) }
            }
        }
        val sanitized = JsonObject(root + ("content" to JsonArray(replaced))).let { updated ->
            updated["structuredContent"]?.let { structured ->
                JsonObject(updated + ("structuredContent" to replacePayloads(structured, replacements)))
            } ?: updated
        }
        ToolResult(resultId, sanitized.toString(), media = saved.takeIf { it.isNotEmpty() })
    }

    private fun isMedia(element: JsonElement): Boolean {
        val item = element as? JsonObject ?: return false
        return item.string("type") in setOf("image", "audio") ||
            item.string("type") == "resource" && (item["resource"] as? JsonObject)?.containsKey("blob") == true
    }

    private fun replacePayloads(element: JsonElement, replacements: Map<String, String>): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { replacePayloads(it.value, replacements) })
        is JsonArray -> JsonArray(element.map { replacePayloads(it, replacements) })
        is JsonPrimitive -> if (element.isString) {
            val raw = element.content
            val value = replacements[raw] ?: raw.takeIf { it.startsWith("data:") && ";base64," in it }
                ?.substringAfter(";base64,")?.let(replacements::get)
            value?.let(::JsonPrimitive) ?: element
        } else element
    }

    private fun resourceName(uri: String): String? = runCatching { URI(uri).path?.substringAfterLast('/') }
        .getOrNull()?.takeIf(String::isNotBlank)

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
}

/** 先核对解码体积再分配字节数组；错误不回显原始载荷。 */
internal fun decodeMediaBase64(raw: String?, maxBytes: Long = MAX_WORKSPACE_MEDIA_BYTES): ByteArray {
    if (raw.isNullOrEmpty()) throw WorkspaceMediaException("BASE64_MISSING", "媒体缺少 base64 载荷")
    val padding = raw.takeLast(2).count { it == '=' }
    val estimated = raw.length.toLong() * 3 / 4 - padding
    if (estimated > maxBytes) throw WorkspaceMediaException("TOO_LARGE", "媒体大小超过工作区写入上限（$maxBytes 字节）")
    val bytes = try { Base64.getDecoder().decode(raw) } catch (_: IllegalArgumentException) {
        throw WorkspaceMediaException("BASE64_INVALID", "媒体 base64 解码失败")
    }
    if (bytes.size.toLong() > maxBytes) throw WorkspaceMediaException("TOO_LARGE", "媒体大小超过工作区写入上限（$maxBytes 字节）")
    return bytes
}
