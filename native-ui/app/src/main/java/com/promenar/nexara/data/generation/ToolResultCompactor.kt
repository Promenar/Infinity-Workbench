package com.promenar.nexara.data.generation

import com.promenar.nexara.data.remote.protocol.ProtocolMessage

/** 面向压缩决策的近似 token 估算：ASCII 约 4 字符 1 token，其余字符按 1 token 计。 */
object ApproximateTokenEstimator {
    fun estimate(text: String): Int {
        var ascii = 0
        var other = 0
        text.forEach { if (it.code < 0x80) ascii++ else other++ }
        return (ascii + 3) / 4 + other
    }

    fun estimate(message: ProtocolMessage): Int =
        MESSAGE_OVERHEAD_TOKENS +
            estimate(message.content) +
            estimate(message.reasoning.orEmpty()) +
            message.toolCalls.orEmpty().sumOf { estimate(it.name) + estimate(it.arguments) }

    private const val MESSAGE_OVERHEAD_TOKENS = 4
}

/**
 * 长工具循环超出模型上下文时，由旧到新把早期工具结果替换为占位说明。
 *
 * 只改写发给 Provider 的协议消息，不改动已持久化的消息；最近 [keepRecentToolResults] 条工具结果、
 * 系统提示、用户消息与助手的工具调用结构始终保留，保证 tool_call / tool_result 成对。
 */
object ToolResultCompactor {
    const val ELIDED_TOOL_RESULT =
        "[早期工具结果已省略以节省上下文。如仍需要其中内容，请重新调用相应工具获取。]"

    data class Result(
        val messages: List<ProtocolMessage>,
        val elidedCount: Int,
        val estimatedTokens: Int,
    )

    fun compact(
        messages: List<ProtocolMessage>,
        budgetTokens: Int,
        extraTokens: Int = 0,
        keepRecentToolResults: Int = DEFAULT_KEEP_RECENT_TOOL_RESULTS,
    ): Result {
        var total = extraTokens + messages.sumOf(ApproximateTokenEstimator::estimate)
        if (total <= budgetTokens) return Result(messages, 0, total)
        val toolIndexes = messages.indices.filter { messages[it].role == "tool" }
        val candidates = toolIndexes.dropLast(keepRecentToolResults.coerceAtLeast(0))
        val compacted = messages.toMutableList()
        var elided = 0
        for (index in candidates) {
            if (total <= budgetTokens) break
            val original = compacted[index]
            if (original.content == ELIDED_TOOL_RESULT) continue
            val before = ApproximateTokenEstimator.estimate(original)
            val replacement = original.copy(content = ELIDED_TOOL_RESULT)
            val after = ApproximateTokenEstimator.estimate(replacement)
            if (after >= before) continue
            compacted[index] = replacement
            total -= before - after
            elided++
        }
        return Result(compacted, elided, total)
    }

    const val DEFAULT_KEEP_RECENT_TOOL_RESULTS = 4
}
