package com.promenar.infinity.data.exec

import com.promenar.infinity.data.secret.SecretOutputRedactor
import com.promenar.infinity.domain.tool.CommandToolResult
import com.promenar.infinity.domain.tool.ToolOutputStream
import com.promenar.infinity.domain.secret.SecretIoLimits
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

internal fun linuxPythonCommand(code: String): String = "python3 -c '${code.replace("'", "'\\''")}'".also {
    require(it.toByteArray(Charsets.UTF_8).size < 131_072) { "Python 代码引用后超过单参数上限，请写入脚本文件后执行。" }
}

/** 注入后即使槽位被覆盖或销毁，运行中的命令也保持遮蔽，结束后清零副本。 */
internal fun retainSecretEnvironment(sessionId: String?, environment: Map<String, String>, redactor: SecretOutputRedactor?): () -> Unit {
    if (sessionId == null || redactor == null || environment.isEmpty()) return {}
    val id = "exec-${UUID.randomUUID()}"
    environment.values.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        try { redactor.registerJobInput(sessionId, id, bytes) } finally { bytes.fill(0) }
    }
    return { redactor.clearJob(sessionId, id) }
}

/** 两路输出使用独立尾部，避免 stdout 与 stderr 交错打断匹配。 */
internal fun secretCommandOutput(sessionId: String, redactor: SecretOutputRedactor): CommandOutputTransform {
    fun layer(): CommandOutputTransform {
        val stdout = redactor.stream(sessionId)
        val stderr = redactor.stream(sessionId)
        return CommandOutputTransform(
            { kind, text -> if (kind == ToolOutputStream.STDOUT) stdout.accept(text) else stderr.accept(text) },
            { kind -> if (kind == ToolOutputStream.STDOUT) stdout.finish() else stderr.finish() },
            { text -> redactor.redact(sessionId, text) },
        )
    }
    val raw = layer()
    return CommandOutputTransform(
        raw.accept, raw.finish, raw.snapshot, normalized = ::layer,
        // 百分号编码是当前遮蔽形式中最长的一种，每字节最多三个字符。
        normalizationTailChars = SecretIoLimits.MAX_SECRET_BYTES * 3,
    )
}

internal fun redactCommandJson(sessionId: String, value: JsonObject, redactor: SecretOutputRedactor): JsonObject {
    fun json(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { json(it.value) })
        is JsonArray -> JsonArray(value.map(::json))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(redactor.redact(sessionId, value.content)) else value
    }
    return json(value) as JsonObject
}

internal fun CommandToolResult.redacted(sessionId: String?, redactor: SecretOutputRedactor?): CommandToolResult {
    if (sessionId == null || redactor == null) return this
    fun clean(value: String) = redactor.redact(sessionId, value)
    return copy(stdout = clean(stdout), stderr = clean(stderr), guidance = guidance?.let(::clean),
        backendFields = redactCommandJson(sessionId, backendFields, redactor))
}
