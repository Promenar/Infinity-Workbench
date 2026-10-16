package com.promenar.nexara.data.generation

import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** 仅替换请求副本的结果正文；调用结构、签名和 ID 始终保持原样。 */
class InFlightToolContextCompressor {
    private val summaries = mutableMapOf<String, String>()

    suspend fun compact(
        messages: List<ProtocolMessage>,
        budgetTokens: Int,
        extraTokens: Int = 0,
        keepRecentRounds: Int = 3,
        summarize: suspend (String) -> String? = { null },
    ): List<ProtocolMessage> {
        var total = extraTokens + messages.sumOf(ApproximateTokenEstimator::estimate)
        if (total <= budgetTokens) return messages
        val roundStarts = messages.indices.filter { !messages[it].toolCalls.isNullOrEmpty() }
        val protectedFrom = roundStarts.takeLast(keepRecentRounds.coerceAtLeast(0)).firstOrNull()
            ?: if (keepRecentRounds > 0) 0 else messages.size
        val result = messages.toMutableList()
        for (index in messages.indices) {
            if (total <= budgetTokens) break
            val message = messages[index]
            if (index >= protectedFrom || message.role != "tool" || message.content.length <= 512) continue
            val content = message.content
            val key = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val folded = summaries[key] ?: run {
                val summary = try {
                    withTimeoutOrNull(15_000) { summarize(headTail(content, 1800)) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                val outputId = Regex("output_id=out_[a-f0-9]{64}").find(content)?.value.orEmpty()
                val replacement = summary?.trim()?.takeIf { it.isNotEmpty() && it.length < content.length / 2 }
                    ?.let { "[Tool result summary; original_chars=${content.length}]\n${it.take(768)}\n$outputId" }
                    ?: headTail(content, 384)
                summaries[key] = replacement
                if (summaries.size > 512) summaries.remove(summaries.keys.first())
                replacement
            }
            val before = ApproximateTokenEstimator.estimate(message)
            val replacement = message.copy(content = folded)
            val after = ApproximateTokenEstimator.estimate(replacement)
            if (after < before) { result[index] = replacement; total -= before - after }
        }
        return result
    }

    companion object {
        fun headTail(content: String, retainedChars: Int = 384): String {
            if (content.length <= retainedChars) return content
            val half = retainedChars / 2
            val headEnd = if (half > 0 && content[half - 1].isHighSurrogate()) half - 1 else half
            val tailStart = (content.length - half).let { if (content[it].isLowSurrogate()) it + 1 else it }
            return content.substring(0, headEnd) + "\n[Truncated tool result; original_chars=${content.length}]\n" + content.substring(tailStart)
        }
    }
}
