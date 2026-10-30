package com.promenar.infinity.data.secret

import com.promenar.infinity.domain.secret.SecretIoLimits
import com.promenar.infinity.domain.secret.SecretSource
import com.promenar.infinity.domain.tool.CommandToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 命令捕获配置只描述槽位与来源，不保存输出内容。 */
data class SecretCapture(val name: String, val label: String, val from: String, val path: String? = null) {
    init {
        require(SecretIoLimits.SLOT_NAME.matches(name) && label.isNotBlank() && label.length <= SecretIoLimits.MAX_LABEL_CHARS &&
            from in setOf("stdout", "file") && (from != "file" || !path.isNullOrBlank()) && path?.contains('\u0000') != true) {
            "SECRET_CAPTURE_INVALID"
        }
    }

    suspend fun complete(
        sessionId: String,
        vault: SessionSecretVault,
        result: CommandToolResult,
        readFile: suspend (String) -> ByteArray,
    ): CommandToolResult {
        val redactor = SecretOutputRedactor(vault)
        val stdoutBytes = result.stdout.trimEnd('\r', '\n').toByteArray(Charsets.UTF_8)
        var captured: ByteArray? = null
        try {
            if (!result.succeeded) return sanitized(result, redactor, sessionId, "SECRET_CAPTURE_COMMAND_FAILED", "命令未成功，未捕获保密信息。")
            if (from == "stdout" && (result.stdoutTruncated || (result.stdoutOriginalLength ?: result.stdout.length.toLong()) > result.stdout.length)) {
                return sanitized(result, redactor, sessionId, "SECRET_CAPTURE_INCOMPLETE", "标准输出不完整，未捕获保密信息。")
            }
            captured = if (from == "stdout") stdoutBytes else try {
                readFile(path!!)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return sanitized(result, redactor, sessionId, "SECRET_CAPTURE_READ_FAILED", "无法读取待捕获文件。")
            }
            if (captured.isEmpty()) return sanitized(result, redactor, sessionId, "SECRET_EMPTY", "捕获内容为空。")
            if (captured.size > SecretIoLimits.MAX_SECRET_BYTES) return sanitized(result, redactor, sessionId, "SECRET_TOO_LARGE", "捕获内容超过保密库容量上限。")
            vault.put(sessionId, name, label, if (from == "stdout") SecretSource.CommandStdout("命令标准输出") else SecretSource.File(path!!), captured)
            return sanitized(result, redactor, sessionId, null, if (from == "file") "已捕获文件内容；文件保留在原位置，请按需要自行删除。" else null)
                .copy(stdout = "‹已捕获为 $name · ${captured.size} 字节›", stdoutOriginalLength = null, stdoutTruncated = false)
        } finally {
            captured?.fill(0)
            stdoutBytes.fill(0)
        }
    }

    private fun sanitized(result: CommandToolResult, redactor: SecretOutputRedactor, sessionId: String, errorCode: String?, guidance: String?): CommandToolResult {
        fun clean(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.entries.associate { redactor.redact(sessionId, it.key) to clean(it.value) })
            is JsonArray -> JsonArray(element.map(::clean))
            is JsonPrimitive -> if (element.isString) JsonPrimitive(redactor.redact(sessionId, element.content)) else element
        }
        return result.copy(
            errorCode = errorCode,
            guidance = guidance,
            stdout = "",
            stdoutOriginalLength = null,
            stdoutTruncated = false,
            // 失败候选可能仅以子串出现在诊断里，尚未入库时无法安全推断遮蔽边界。
            stderr = if (errorCode == null) redactor.redact(sessionId, result.stderr) else "",
            stderrOriginalLength = if (errorCode == null) result.stderrOriginalLength else null,
            stderrTruncated = errorCode == null && result.stderrTruncated,
            backendFields = if (errorCode == null) clean(result.backendFields) as JsonObject else JsonObject(emptyMap()),
        )
    }

    companion object {
        val schema: String = """
            "capture_secret": {
              "type": "object",
              "description": "将命令产生的保密信息捕获到本会话保密库；禁止后台执行，输出只返回占位。",
              "properties": {
                "name": {"type": "string", "description": "环境变量槽位名，须匹配 [A-Z][A-Z0-9_]{0,63}。"},
                "label": {"type": "string", "description": "面向用户的非空标签，最多 ${SecretIoLimits.MAX_LABEL_CHARS} 个字符。"},
                "from": {"type": "string", "enum": ["stdout", "file"]},
                "path": {"type": "string", "description": "from=file 时必填；捕获后原文件保留。"}
              },
              "required": ["name", "label", "from"],
              "additionalProperties": false
            }
        """.trimIndent()

        fun parse(args: JsonObject): SecretCapture? {
            val raw = args[SecretIoLimits.CAPTURE_PARAMETER] ?: return null
            val obj = raw as? JsonObject ?: throw IllegalArgumentException("SECRET_CAPTURE_INVALID")
            fun string(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(obj.keys.all { it in setOf("name", "label", "from", "path") } && ("path" !in obj || string("path") != null)) { "SECRET_CAPTURE_INVALID" }
            return SecretCapture(string("name") ?: throw IllegalArgumentException("SECRET_CAPTURE_INVALID"),
                string("label") ?: throw IllegalArgumentException("SECRET_CAPTURE_INVALID"),
                string("from") ?: throw IllegalArgumentException("SECRET_CAPTURE_INVALID"), string("path"))
        }
    }
}
