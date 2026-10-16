package com.promenar.nexara.ui.chat

import com.promenar.nexara.data.model.ExecutionStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 仅从明确的工具信封取名，参数里的普通 name 字段不能当成工具名。 */
internal fun inferredToolName(step: ExecutionStep): String? {
    step.toolName?.takeIf(String::isNotBlank)?.let { return it }
    for (payload in listOfNotNull(step.data, step.toolArgs, step.content)) {
        val envelope = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: continue
        fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        envelope.text("tool_name")?.let { return it }
        envelope.text("tool")?.let { return it }
        (envelope["function"] as? JsonObject)?.text("name")?.let { return it }
        if ("arguments" in envelope || "parameters" in envelope) envelope.text("name")?.let { return it }
    }
    return null
}

internal data class ToolInvocationPresentation(val steps: List<ExecutionStep>) {
    val name: String? get() = steps.firstNotNullOfOrNull(::inferredToolName)
    val call: ExecutionStep? get() = steps.firstOrNull { it.type == "tool_call" }
    val results: List<ExecutionStep> get() = steps.filter { it.type == "tool_result" || it.type == "error" }
    val failed: Boolean get() = results.any { it.type == "error" }
    val complete: Boolean get() = results.isNotEmpty()
    val durationMs: Long? get() {
        val start = call?.timestamp?.takeIf { it > 0 } ?: return null
        val end = results.maxOfOrNull { it.timestamp } ?: return null
        return (end - start).takeIf { it >= 0 }
    }
}

/** 调用 ID 优先；兼容 res_步骤ID 与按名称顺序返回的历史记录，不把结果再计作一次调用。 */
internal fun toolInvocations(steps: List<ExecutionStep>): List<ToolInvocationPresentation> {
    val groups = mutableListOf<MutableList<ExecutionStep>>()
    steps.filter { it.type == "tool_call" || it.type == "tool_result" || it.type == "error" }
        .distinctBy { it.id }.forEach { step ->
        val callId = step.toolCallId?.takeIf(String::isNotBlank)
        val name = inferredToolName(step)
        val existing = groups.firstOrNull { group ->
            callId != null && group.any { it.toolCallId == callId }
        } ?: if (step.type != "tool_call") {
            groups.firstOrNull { group ->
                group.any { it.type == "tool_call" && step.id == "res_${it.id}" }
            } ?: groups.firstOrNull { group ->
                callId == null && group.any { it.type == "tool_call" } &&
                    group.none { it.type == "tool_result" || it.type == "error" } &&
                    (name == null || group.firstNotNullOfOrNull(::inferredToolName) == name)
            }
        } else null
        if (existing != null) existing.add(step) else groups.add(mutableListOf(step))
    }
    return groups.map { ToolInvocationPresentation(it.toList()) }
}

/** 多工具行显示实际调用区间；缺少起止事件时不猜测耗时。 */
internal fun toolGroupDurationMs(invocations: List<ToolInvocationPresentation>, nowMs: Long? = null): Long? {
    if (invocations.isEmpty()) return null
    if (invocations.any { !it.complete }) {
        if (nowMs == null || invocations.any { it.call == null || it.call!!.timestamp <= 0 }) return null
        return (nowMs - invocations.minOf { it.call!!.timestamp }).takeIf { it >= 0 }
    }
    if (invocations.any { it.durationMs == null }) return null
    val start = invocations.minOf { requireNotNull(it.call).timestamp }
    val end = invocations.flatMap { it.results }.maxOf { it.timestamp }
    return (end - start).takeIf { it >= 0 }
}

/** 工具耗时文案：不足 1 秒显示毫秒，否则显示保留一位小数的秒数。 */
internal fun toolDurationText(durationMs: Long): ToolDurationText =
    if (durationMs < 1_000) {
        ToolDurationText(com.promenar.nexara.R.string.chat_tool_duration, listOf(durationMs))
    } else {
        ToolDurationText(
            com.promenar.nexara.R.string.chat_tool_duration_seconds,
            listOf(String.format(java.util.Locale.ROOT, "%.1f", durationMs / 1_000.0)),
        )
    }

/** 待本地化的文案：资源 ID 加格式参数。 */
internal data class ToolDurationText(val resId: Int, val args: List<Any>)
