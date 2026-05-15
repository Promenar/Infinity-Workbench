package com.promenar.nexara.data.remote

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope

data class ThinkingResult(
    val content: String,
    val reasoning: String
)

class ThinkingDetector {

    private var buffer: String = ""
    private var state: State = State.OUTSIDE

    private enum class State {
        OUTSIDE,
        INSIDE
    }

    companion object {
        private val THINK_OPEN_PATTERNS: List<ThinkPattern> = listOf(
            ThinkPattern(Regex("<!--\\s*THINKING_START\\s*-->", RegexOption.IGNORE_CASE), 23),
            ThinkPattern(Regex("<think(?=\\s|>|/)", RegexOption.IGNORE_CASE), 7),
            ThinkPattern(Regex("<thought(?=\\s|>|/)", RegexOption.IGNORE_CASE), 9),
        )

        private val THINK_CLOSE_PATTERNS: List<ThinkPattern> = listOf(
            ThinkPattern(Regex("<!--\\s*THINKING_END\\s*-->", RegexOption.IGNORE_CASE), 21),
            ThinkPattern(Regex("</think/?\\s*>", RegexOption.IGNORE_CASE), 8),
            ThinkPattern(Regex("</thought/?\\s*>", RegexOption.IGNORE_CASE), 10),
        )

        private const val MAX_TAG_PREFIX_LEN = 30
    }

    private data class ThinkPattern(
        val regex: Regex,
        val tagLength: Int
    )

    private data class TagMatchResult(
        val found: Boolean,
        val index: Int,
        val tagLength: Int
    )

    /**
     * 快速路径：若缓冲区为空、状态为 OUTSIDE、且 chunk 不含 '<'，
     * 直接原样返回 content，完全跳过正则扫描和状态机。
     * 这覆盖了绝大多数非思考模型的正常流式输出。
     */
    private fun tryFastPath(chunk: String): ThinkingResult? {
        if (buffer.isEmpty() && state == State.OUTSIDE && !chunk.contains('<')) {
            return ThinkingResult(content = chunk, reasoning = "")
        }
        return null
    }

    fun process(chunk: String): ThinkingResult {
        if (chunk.isEmpty()) return ThinkingResult(content = "", reasoning = "")

        // 快速路径：95%+ 的正常文本直通，零开销
        tryFastPath(chunk)?.let { return it }

        buffer += chunk

        val outputContent = StringBuilder()
        val outputReasoning = StringBuilder()

        var loopGuard = 0
        while (loopGuard++ < 500 && buffer.isNotEmpty()) {
            when (state) {
                State.OUTSIDE -> {
                    val matchResult = findThinkOpen(buffer)

                    if (matchResult.found) {
                        outputContent.append(buffer.substring(0, matchResult.index))
                        buffer = buffer.substring(matchResult.index + matchResult.tagLength)
                        state = State.INSIDE
                        continue
                    }

                    val safeEnd = findSafeOutputEnd(buffer)
                    if (safeEnd > 0) {
                        outputContent.append(buffer.substring(0, safeEnd))
                        buffer = buffer.substring(safeEnd)
                    }
                    break
                }

                State.INSIDE -> {
                    val matchResult = findThinkClose(buffer)

                    if (matchResult.found) {
                        outputReasoning.append(buffer.substring(0, matchResult.index))
                        buffer = buffer.substring(matchResult.index + matchResult.tagLength)
                        state = State.OUTSIDE
                        continue
                    }

                    val safeEnd = findSafeOutputEnd(buffer)
                    if (safeEnd > 0) {
                        outputReasoning.append(buffer.substring(0, safeEnd))
                        buffer = buffer.substring(safeEnd)
                    }
                    break
                }
            }
        }

        return ThinkingResult(
            content = outputContent.toString(),
            reasoning = outputReasoning.toString()
        )
    }

    fun flush(): ThinkingResult {
        val result = if (state == State.INSIDE) {
            ThinkingResult(content = "", reasoning = buffer)
        } else {
            ThinkingResult(content = buffer, reasoning = "")
        }

        buffer = ""
        state = State.OUTSIDE
        return result
    }

    fun reset() {
        buffer = ""
        state = State.OUTSIDE
    }

    fun getState(): DetectorState = DetectorState(
        state = if (state == State.OUTSIDE) "OUTSIDE" else "INSIDE",
        bufferLength = buffer.length
    )

    data class DetectorState(
        val state: String,
        val bufferLength: Int
    )

    suspend fun processFlow(
        input: Channel<String>,
        output: Channel<ThinkingResult>
    ) {
        coroutineScope {
            try {
                for (chunk in input) {
                    val result = process(chunk)
                    if (result.content.isNotEmpty() || result.reasoning.isNotEmpty()) {
                        output.send(result)
                    }
                }
                val remaining = flush()
                if (remaining.content.isNotEmpty() || remaining.reasoning.isNotEmpty()) {
                    output.send(remaining)
                }
            } finally {
                output.close()
            }
        }
    }

    private fun findThinkOpen(text: String): TagMatchResult {
        var bestIndex = -1
        var bestTagLength = 0

        for (pattern in THINK_OPEN_PATTERNS) {
            val match = pattern.regex.find(text)
            if (match != null && (bestIndex == -1 || match.range.first < bestIndex)) {
                bestIndex = match.range.first
                val closeBracket = text.indexOf('>', bestIndex)
                if (closeBracket != -1) {
                    bestTagLength = closeBracket - bestIndex + 1
                } else {
                    bestIndex = -1
                }
            }
        }

        return TagMatchResult(
            found = bestIndex != -1,
            index = bestIndex,
            tagLength = bestTagLength
        )
    }

    private fun findThinkClose(text: String): TagMatchResult {
        var bestIndex = -1
        var bestTagLength = 0

        for (pattern in THINK_CLOSE_PATTERNS) {
            val match = pattern.regex.find(text)
            if (match != null && (bestIndex == -1 || match.range.first < bestIndex)) {
                bestIndex = match.range.first
                bestTagLength = match.value.length
            }
        }

        return TagMatchResult(
            found = bestIndex != -1,
            index = bestIndex,
            tagLength = bestTagLength
        )
    }

    private fun findSafeOutputEnd(text: String): Int {
        if (text.isEmpty()) return 0

        val lastOpenBracket = text.lastIndexOf('<')
        if (lastOpenBracket == -1) {
            return text.length
        }

        val afterBracket = text.substring(lastOpenBracket + 1)
        val mightBeIncompleteTag = when {
            afterBracket.startsWith("/") -> {
                val afterSlash = afterBracket.substring(1)
                afterSlash.length <= "thought".length
            }
            afterBracket.isEmpty() -> true
            else -> {
                afterBracket.length <= "thought".length &&
                listOf("think", "thought", "!").any { p ->
                    p.startsWith(afterBracket) || afterBracket.startsWith(p.take(maxOf(afterBracket.length, 1)))
                }
            }
        }
        if (mightBeIncompleteTag) return lastOpenBracket
        return text.length
    }
}
