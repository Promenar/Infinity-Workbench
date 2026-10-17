package com.promenar.nexara.data.generation

import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.PromptRequest

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

    fun estimate(prompt: PromptRequest): Int = (
        prompt.messages.sumOf { estimate(it).toLong() } +
            prompt.tools.orEmpty().sumOf {
                estimate(it.function.name + it.function.description + it.function.parameters).toLong()
            }
        ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private const val MESSAGE_OVERHEAD_TOKENS = 4
}

