package com.promenar.nexara.domain.tool

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 输出超限时被去掉的部分。 */
enum class CommandTruncation(val wire: String) {
    HEAD_REMOVED("head_removed"),
    TAIL_REMOVED("tail_removed"),
}

/**
 * 命令类工具（termux_exec、ssh_exec 及内置执行后端）共用的结果格式。
 * 根因字段在前，失败摘要与日志只取前段时仍可见；后端专有字段排在共有字段之后。
 */
data class CommandToolResult(
    val errorCode: String? = null,
    val guidance: String? = null,
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val stdout: String = "",
    val stderr: String = "",
    /** 原始长度（UTF-16 单元）；后端无法得知时为 null。 */
    val stdoutOriginalLength: Long? = stdout.length.toLong(),
    val stderrOriginalLength: Long? = stderr.length.toLong(),
    val stdoutTruncated: Boolean = (stdoutOriginalLength ?: 0L) > stdout.length,
    val stderrTruncated: Boolean = (stderrOriginalLength ?: 0L) > stderr.length,
    val removedWhenTruncated: CommandTruncation = CommandTruncation.HEAD_REMOVED,
    val backendFields: JsonObject = JsonObject(emptyMap()),
) {
    val truncated: Boolean get() = stdoutTruncated || stderrTruncated

    /** 命令本身未出错、未超时且退出码为 0 才算成功。 */
    val succeeded: Boolean get() = errorCode == null && !timedOut && exitCode == 0

    val toolStatus: String get() = if (succeeded || errorCode == null && (backendFields["state"] as? JsonPrimitive)?.content == "running") "success" else "error"

    fun toJson(): JsonObject = buildJsonObject {
        put("error_code", errorCode?.let(::JsonPrimitive) ?: JsonNull)
        put("guidance", guidance?.let(::JsonPrimitive) ?: JsonNull)
        put("exit_code", exitCode?.let(::JsonPrimitive) ?: JsonNull)
        put("timed_out", timedOut)
        put("stderr", stderr)
        put("stdout", stdout)
        put("stdout_original_length", stdoutOriginalLength?.let(::JsonPrimitive) ?: JsonNull)
        put("stderr_original_length", stderrOriginalLength?.let(::JsonPrimitive) ?: JsonNull)
        put("length_unit", "UTF-16")
        put("stdout_truncated", stdoutTruncated)
        put("stderr_truncated", stderrTruncated)
        put("truncated", truncated)
        put("truncation", if (truncated) removedWhenTruncated.wire else "none")
        backendFields.forEach { (key, value) -> put(key, value) }
    }
}
