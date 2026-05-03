package com.promenar.nexara.data.remote.parser

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("StreamBufferManager")
class StreamBufferManagerTest {

    private lateinit var manager: StreamBufferManager

    @BeforeEach
    fun setUp() {
        manager = StreamBufferManager()
    }

    @Nested
    @DisplayName("Plain text without thinking markers")
    inner class PlainText {

        @Test
        @DisplayName("short text is held in rawBuffer for jitter protection")
        fun shortTextHeld() {
            val result = manager.append("Hello, world!")
            assertThat(result.content).isEmpty()
            assertThat(result.rawBuffer).isEqualTo("Hello, world!")
            assertThat(result.thinking).isEmpty()
            assertThat(result.isComplete).isTrue()
        }

        @Test
        @DisplayName("long text releases safe portion, holds back potential marker overlap")
        fun longTextPartialRelease() {
            val holdback = StreamBufferManager.THINKING_START.length - 1
            val text = "A".repeat(50)
            val result = manager.append(text)
            assertThat(result.content).isEqualTo("A".repeat(50 - holdback))
            assertThat(result.rawBuffer).isEqualTo("A".repeat(holdback))
        }

        @Test
        @DisplayName("multiple short chunks accumulate in rawBuffer")
        fun multipleShortChunks() {
            manager.append("Hello")
            manager.append(", ")
            val result = manager.append("world!")
            assertThat(result.content).isEmpty()
            assertThat(result.rawBuffer).isEqualTo("Hello, world!")
        }

        @Test
        @DisplayName("flush releases all held content")
        fun flushReleasesAll() {
            manager.append("Hello, world!")
            val result = manager.flush()
            assertThat(result.content).isEqualTo("Hello, world!")
            assertThat(result.rawBuffer).isEmpty()
        }

        @Test
        @DisplayName("empty chunk produces empty result")
        fun emptyChunk() {
            val result = manager.append("")
            assertThat(result.content).isEmpty()
            assertThat(result.rawBuffer).isEmpty()
        }
    }

    @Nested
    @DisplayName("Complete thinking block in single chunk")
    inner class CompleteThinkingBlock {

        @Test
        @DisplayName("thinking block with content after it")
        fun thinkingWithContent() {
            val input = "${StreamBufferManager.THINKING_START}let me think${StreamBufferManager.THINKING_END}The answer is 42."
            val result = manager.append(input)
            assertThat(result.thinking).isEqualTo("let me think")
            assertThat(result.content).isEqualTo("The answer is 42.")
            assertThat(result.isComplete).isTrue()
        }

        @Test
        @DisplayName("thinking block with no content after it")
        fun thinkingOnly() {
            val input = "${StreamBufferManager.THINKING_START}just thinking${StreamBufferManager.THINKING_END}"
            val result = manager.append(input)
            assertThat(result.thinking).isEqualTo("just thinking")
            assertThat(result.content).isEmpty()
            assertThat(result.isComplete).isTrue()
        }

        @Test
        @DisplayName("content before and after thinking block")
        fun contentBeforeAndAfter() {
            val input = "before${StreamBufferManager.THINKING_START}think${StreamBufferManager.THINKING_END}after"
            val result = manager.append(input)
            assertThat(result.thinking).isEqualTo("think")
            assertThat(result.content).isEqualTo("beforeafter")
            assertThat(result.isComplete).isTrue()
        }
    }

    @Nested
    @DisplayName("Cross-chunk THINKING_START splitting (holdback)")
    inner class CrossChunkStartTag {

        @Test
        @DisplayName("THINKING_START split across two chunks is reassembled via holdback")
        fun startTagSplitAcrossTwoChunks() {
            val marker = StreamBufferManager.THINKING_START
            val splitPoint = marker.length / 2
            val part1 = marker.substring(0, splitPoint)
            val part2 = marker.substring(splitPoint) + "thinking content" +
                StreamBufferManager.THINKING_END + "result"

            val r1 = manager.append(part1)
            assertThat(r1.content).isEmpty()
            assertThat(r1.rawBuffer).isEqualTo(part1)

            val r2 = manager.append(part2)
            assertThat(r2.thinking).isEqualTo("thinking content")
            assertThat(r2.content).isEqualTo("result")
            assertThat(r2.isComplete).isTrue()
        }

        @Test
        @DisplayName("prefix text + split THINKING_START across chunks")
        fun prefixWithSplitStart() {
            val marker = StreamBufferManager.THINKING_START

            val r1 = manager.append("Hello ")
            assertThat(r1.content).isEmpty()
            assertThat(r1.rawBuffer).isEqualTo("Hello ")

            val r2 = manager.append(marker.substring(0, 10))
            assertThat(r2.content).isEmpty()

            val r3 = manager.append(marker.substring(10) + "deep thoughts" +
                StreamBufferManager.THINKING_END + "answer")
            assertThat(r3.thinking).isEqualTo("deep thoughts")
            assertThat(r3.content).isEqualTo("Hello answer")
            assertThat(r3.isComplete).isTrue()
        }

        @Test
        @DisplayName("THINKING_START split character by character still detects marker")
        fun startTagSplitCharByChar() {
            val marker = StreamBufferManager.THINKING_START
            for (c in marker) {
                manager.append(c.toString())
            }
            assertThat(manager.getState().inThinking).isTrue()

            manager.append("thoughts")
            manager.append(StreamBufferManager.THINKING_END)
            manager.append("output")

            val state = manager.stateFlow.value
            assertThat(state.thinking).isEqualTo("thoughts")
            assertThat(state.content).isEqualTo("output")
            assertThat(state.isComplete).isTrue()
        }
    }

    @Nested
    @DisplayName("THINKING_END within thinking block")
    inner class ThinkingEndInBlock {

        @Test
        @DisplayName("complete THINKING_END in single chunk within thinking block")
        fun completeEndInThinking() {
            manager.append(StreamBufferManager.THINKING_START)
            manager.append("step 1. step 2.")
            val result = manager.append(StreamBufferManager.THINKING_END + "final answer")
            assertThat(result.thinking).isEqualTo("step 1. step 2.")
            assertThat(result.content).isEqualTo("final answer")
            assertThat(result.isComplete).isTrue()
        }

        @Test
        @DisplayName("multiple appends within thinking block accumulate")
        fun multipleAppendsInThinking() {
            manager.append(StreamBufferManager.THINKING_START)
            manager.append("step 1. ")
            manager.append("step 2. ")
            val result = manager.append(
                StreamBufferManager.THINKING_END + "answer"
            )
            assertThat(result.thinking).isEqualTo("step 1. step 2. ")
            assertThat(result.content).isEqualTo("answer")
        }

        @Test
        @DisplayName("content after thinking block continues to accumulate")
        fun contentAfterThinkingAccumulates() {
            val marker = StreamBufferManager.THINKING_START
            manager.append(marker + "think" + StreamBufferManager.THINKING_END + "part1 ")
            val result = manager.append("part2")
            assertThat(result.content).isEqualTo("part1 part2")
            assertThat(result.thinking).isEqualTo("think")
            assertThat(result.isComplete).isTrue()
        }
    }

    @Nested
    @DisplayName("Partial thinking block (not closed)")
    inner class PartialThinkingBlock {

        @Test
        @DisplayName("unclosed thinking block returns isComplete=false")
        fun unclosedThinking() {
            val result = manager.append(StreamBufferManager.THINKING_START + "thinking...")
            assertThat(result.thinking).isEqualTo("thinking...")
            assertThat(result.isComplete).isFalse()
        }
    }

    @Nested
    @DisplayName("Flush")
    inner class Flush {

        @Test
        @DisplayName("flush releases held buffer as content")
        fun flushHeldBuffer() {
            manager.append("some text without markers")
            val result = manager.flush()
            assertThat(result.content).isEqualTo("some text without markers")
            assertThat(result.isComplete).isTrue()
            assertThat(result.rawBuffer).isEmpty()
        }

        @Test
        @DisplayName("flush on empty buffer returns existing accumulated state")
        fun flushEmptyBuffer() {
            manager.append("hello")
            manager.flush()
            val result = manager.flush()
            assertThat(result.content).isEqualTo("hello")
            assertThat(result.rawBuffer).isEmpty()
        }

        @Test
        @DisplayName("flush unclosed thinking block keeps thinking in thinking field")
        fun flushUnclosedThinking() {
            manager.append(StreamBufferManager.THINKING_START + "partial thinking")
            val state = manager.getState()
            assertThat(state.inThinking).isTrue()

            val result = manager.flush()
            assertThat(result.isComplete).isTrue()
            assertThat(result.thinking).isEqualTo("partial thinking")
            assertThat(result.content).isEmpty()
            assertThat(result.rawBuffer).isEmpty()
        }
    }

    @Nested
    @DisplayName("Reset")
    inner class Reset {

        @Test
        @DisplayName("reset clears all state")
        fun resetClearsState() {
            manager.append(StreamBufferManager.THINKING_START + "think")
            manager.reset()
            val state = manager.getState()
            assertThat(state.inThinking).isFalse()
            assertThat(state.thinkingComplete).isFalse()
            assertThat(state.bufferLength).isEqualTo(0)

            val flowVal = manager.stateFlow.value
            assertThat(flowVal.thinking).isEmpty()
            assertThat(flowVal.content).isEmpty()
            assertThat(flowVal.isComplete).isTrue()
        }

        @Test
        @DisplayName("reuse after reset works correctly")
        fun reuseAfterReset() {
            manager.append(StreamBufferManager.THINKING_START + "old")
            manager.reset()

            val result = manager.append(
                StreamBufferManager.THINKING_START + "new thinking" +
                    StreamBufferManager.THINKING_END + "new content"
            )
            assertThat(result.thinking).isEqualTo("new thinking")
            assertThat(result.content).isEqualTo("new content")
        }
    }

    @Nested
    @DisplayName("StateFlow updates")
    inner class StateFlowUpdates {

        @Test
        @DisplayName("stateFlow reflects latest parsed content")
        fun stateFlowReflectsLatest() = runTest {
            manager.append("hello ")
            manager.append("world")
            assertThat(manager.stateFlow.value.rawBuffer).isEqualTo("hello world")
        }

        @Test
        @DisplayName("stateFlow updates on thinking block detection")
        fun stateFlowOnThinkingDetection() = runTest {
            manager.append(StreamBufferManager.THINKING_START + "think")
            val flowVal = manager.stateFlow.value
            assertThat(flowVal.thinking).isEqualTo("think")
            assertThat(flowVal.isComplete).isFalse()
        }

        @Test
        @DisplayName("stateFlow reflects flush result")
        fun stateFlowOnFlush() = runTest {
            manager.append("accumulated text")
            manager.flush()
            assertThat(manager.stateFlow.value.content).isEqualTo("accumulated text")
            assertThat(manager.stateFlow.value.rawBuffer).isEmpty()
        }
    }

    @Nested
    @DisplayName("getState debug info")
    inner class GetState {

        @Test
        @DisplayName("initial state has no thinking")
        fun initialState() {
            val state = manager.getState()
            assertThat(state.inThinking).isFalse()
            assertThat(state.thinkingComplete).isFalse()
            assertThat(state.bufferLength).isEqualTo(0)
        }

        @Test
        @DisplayName("state reflects in-thinking flag")
        fun inThinkingState() {
            manager.append(StreamBufferManager.THINKING_START)
            assertThat(manager.getState().inThinking).isTrue()
        }

        @Test
        @DisplayName("state reflects thinking-complete flag")
        fun thinkingCompleteState() {
            manager.append(
                StreamBufferManager.THINKING_START + "done" + StreamBufferManager.THINKING_END
            )
            val state = manager.getState()
            assertThat(state.inThinking).isFalse()
            assertThat(state.thinkingComplete).isTrue()
        }
    }

    @Nested
    @DisplayName("classifyTextHeuristic")
    inner class ClassifyTextHeuristic {

        @Test
        @DisplayName("task complete returns not-thinking with high confidence")
        fun taskComplete() {
            val (isThinking, confidence) = classifyTextHeuristic("some text", true)
            assertThat(isThinking).isFalse()
            assertThat(confidence).isGreaterThan(0.8f)
        }

        @Test
        @DisplayName("long text returns not-thinking")
        fun longText() {
            val longText = "a".repeat(900)
            val (isThinking, _) = classifyTextHeuristic(longText, false)
            assertThat(isThinking).isFalse()
        }

        @Test
        @DisplayName("Chinese thinking pattern detected")
        fun chineseThinkingPattern() {
            val (isThinking, _) = classifyTextHeuristic("首先我需要分析问题", false)
            assertThat(isThinking).isTrue()
        }

        @Test
        @DisplayName("English thinking pattern detected")
        fun englishThinkingPattern() {
            val (isThinking, _) = classifyTextHeuristic("Let me think about this", false)
            assertThat(isThinking).isTrue()
        }

        @Test
        @DisplayName("trailing ellipsis detected as thinking")
        fun trailingEllipsis() {
            val (isThinking, _) = classifyTextHeuristic("analyzing...", false)
            assertThat(isThinking).isTrue()
        }

        @Test
        @DisplayName("short text defaults to thinking")
        fun shortTextDefaultsToThinking() {
            val (isThinking, _) = classifyTextHeuristic("hmm", false)
            assertThat(isThinking).isTrue()
        }
    }
}
