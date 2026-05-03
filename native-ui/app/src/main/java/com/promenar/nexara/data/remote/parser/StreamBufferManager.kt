package com.promenar.nexara.data.remote.parser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ParsedContent(
    val thinking: String = "",
    val content: String = "",
    val isComplete: Boolean = true,
    val rawBuffer: String = ""
)

data class StreamBufferState(
    val inThinking: Boolean = false,
    val thinkingComplete: Boolean = false,
    val bufferLength: Int = 0
)

class StreamBufferManager {

    companion object {
        const val THINKING_START = "<!-- THINKING_START -->"
        const val THINKING_END = "<!-- THINKING_END -->"
    }

    private var buffer: String = ""
    private var thinkingBuffer: String = ""
    private var contentBuffer: String = ""
    private var inThinkingBlock: Boolean = false
    private var thinkingComplete: Boolean = false

    private val _stateFlow = MutableStateFlow(
        ParsedContent(
            thinking = "",
            content = "",
            isComplete = true,
            rawBuffer = ""
        )
    )
    val stateFlow: StateFlow<ParsedContent> = _stateFlow.asStateFlow()

    fun append(chunk: String): ParsedContent {
        buffer += chunk
        val result = parse()
        _stateFlow.value = result
        return result
    }

    private fun parse(): ParsedContent {
        var working = buffer

        if (!inThinkingBlock && !thinkingComplete) {
            val startIdx = working.indexOf(THINKING_START)
            if (startIdx != -1) {
                contentBuffer += working.substring(0, startIdx)
                working = working.substring(startIdx + THINKING_START.length)
                inThinkingBlock = true
                buffer = working
            }
        }

        if (inThinkingBlock) {
            val endIdx = working.indexOf(THINKING_END)
            if (endIdx != -1) {
                thinkingBuffer += working.substring(0, endIdx)
                working = working.substring(endIdx + THINKING_END.length)
                inThinkingBlock = false
                thinkingComplete = true
                buffer = working
                contentBuffer += working
                buffer = ""
            } else {
                thinkingBuffer += working
                buffer = ""
            }
        } else if (thinkingComplete) {
            contentBuffer += working
            buffer = ""
        } else {
            val potentialStartLength = THINKING_START.length - 1
            if (working.length > potentialStartLength) {
                val safeContent = working.substring(0, working.length - potentialStartLength)
                val holdback = working.substring(working.length - potentialStartLength)
                contentBuffer += safeContent
                buffer = holdback
            }
        }

        return ParsedContent(
            thinking = thinkingBuffer,
            content = contentBuffer,
            isComplete = !inThinkingBlock,
            rawBuffer = buffer
        )
    }

    fun flush(): ParsedContent {
        contentBuffer += buffer
        buffer = ""

        val result = ParsedContent(
            thinking = thinkingBuffer,
            content = contentBuffer,
            isComplete = true,
            rawBuffer = ""
        )
        _stateFlow.value = result
        return result
    }

    fun reset() {
        buffer = ""
        thinkingBuffer = ""
        contentBuffer = ""
        inThinkingBlock = false
        thinkingComplete = false
        _stateFlow.value = ParsedContent()
    }

    fun getState(): StreamBufferState {
        return StreamBufferState(
            inThinking = inThinkingBlock,
            thinkingComplete = thinkingComplete,
            bufferLength = buffer.length
        )
    }
}

fun classifyTextHeuristic(
    text: String,
    isTaskComplete: Boolean
): Pair<Boolean, Float> {
    val trimmed = text.trim()

    if (isTaskComplete) {
        return Pair(false, 0.9f)
    }

    if (text.length > 800) {
        return Pair(false, 0.85f)
    }

    val thinkingPatterns = listOf(
        Regex("^(我将|正在|首先|现在|已经|让我|接下来)"),
        Regex("^(I will|I'm going to|First|Now|Starting|Next|Let me)", RegexOption.IGNORE_CASE),
        Regex("\\.\\.\\.\$"),
        Regex("：\$"),
        Regex(":\$")
    )

    for (pattern in thinkingPatterns) {
        if (pattern.containsMatchIn(trimmed)) {
            return Pair(true, 0.7f)
        }
    }

    if (text.length < 300) {
        return Pair(true, 0.5f)
    }

    return Pair(false, 0.6f)
}
