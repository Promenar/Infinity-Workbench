package com.promenar.nexara.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ThinkingDetector")
class ThinkingDetectorTest {

    private lateinit var detector: ThinkingDetector

    @BeforeEach
    fun setUp() {
        detector = ThinkingDetector()
    }

    @Nested
    @DisplayName("Plain text (no tags)")
    inner class PlainText {

        @Test
        @DisplayName("single chunk passes through as content")
        fun singleChunk() {
            val result = detector.process("Hello, world!")
            assertThat(result.content).isEqualTo("Hello, world!")
            assertThat(result.reasoning).isEmpty()
        }

        @Test
        @DisplayName("multiple chunks accumulate as content")
        fun multipleChunks() {
            val r1 = detector.process("Hello")
            val r2 = detector.process(", ")
            val r3 = detector.process("world!")
            assertThat(r1.content + r2.content + r3.content).isEqualTo("Hello, world!")
            assertThat(r1.reasoning + r2.reasoning + r3.reasoning).isEmpty()
        }

        @Test
        @DisplayName("empty chunk returns empty result")
        fun emptyChunk() {
            val result = detector.process("")
            assertThat(result.content).isEmpty()
            assertThat(result.reasoning).isEmpty()
        }
    }

    @Nested
    @DisplayName("<think/> tags")
    inner class ThinkTags {

        @Test
        @DisplayName("single think block in one chunk")
        fun singleBlock() {
            val result = detector.process("before<think/>reasoning here</think/>after")
            assertThat(result.content).isEqualTo("beforeafter")
            assertThat(result.reasoning).isEqualTo("reasoning here")
        }

        @Test
        @DisplayName("think block with attributes")
        fun withAttributes() {
            val result = detector.process("before<think type=\"reasoning\">inner thoughts</think/>after")
            assertThat(result.content).isEqualTo("beforeafter")
            assertThat(result.reasoning).isEqualTo("inner thoughts")
        }

        @Test
        @DisplayName("multiple think blocks")
        fun multipleBlocks() {
            val r1 = detector.process("a<think/>b</think/>c<think/>d</think/>e")
            assertThat(r1.content).isEqualTo("ace")
            assertThat(r1.reasoning).isEqualTo("bd")
        }
    }

    @Nested
    @DisplayName("<thought/> tags")
    inner class ThoughtTags {

        @Test
        @DisplayName("single thought block")
        fun singleBlock() {
            val result = detector.process("before<thought/>deep thinking</thought/>after")
            assertThat(result.content).isEqualTo("beforeafter")
            assertThat(result.reasoning).isEqualTo("deep thinking")
        }

        @Test
        @DisplayName("thought block with attributes")
        fun withAttributes() {
            val result = detector.process("a<thought level=2>reasoning</thought>b")
            assertThat(result.content).isEqualTo("ab")
            assertThat(result.reasoning).isEqualTo("reasoning")
        }
    }

    @Nested
    @DisplayName("<!-- THINKING_START/END --> comments")
    inner class HtmlCommentTags {

        @Test
        @DisplayName("comment-style thinking tags")
        fun commentStyle() {
            val result = detector.process("before<!-- THINKING_START -->thinking<!-- THINKING_END -->after")
            assertThat(result.content).isEqualTo("beforeafter")
            assertThat(result.reasoning).isEqualTo("thinking")
        }

        @Test
        @DisplayName("comment tags with extra whitespace")
        fun commentWithWhitespace() {
            val result = detector.process("a<!--  THINKING_START  -->thoughts<!--  THINKING_END  -->b")
            assertThat(result.content).isEqualTo("ab")
            assertThat(result.reasoning).isEqualTo("thoughts")
        }

        @Test
        @DisplayName("comment tags case insensitive")
        fun commentCaseInsensitive() {
            val result = detector.process("a<!-- thinking_start -->thoughts<!-- thinking_end -->b")
            assertThat(result.content).isEqualTo("ab")
            assertThat(result.reasoning).isEqualTo("thoughts")
        }
    }

    @Nested
    @DisplayName("Cross-chunk boundary handling")
    inner class CrossChunkBoundary {

        @Test
        @DisplayName("open tag split across two chunks")
        fun openTagSplit() {
            val r1 = detector.process("hello<thin")
            val r2 = detector.process("k/>reasoning</think/>world")
            assertThat(r1.content).isEqualTo("hello")
            assertThat(r1.reasoning).isEmpty()
            assertThat(r2.content).isEqualTo("world")
            assertThat(r2.reasoning).isEqualTo("reasoning")
        }

        @Test
        @DisplayName("close tag split across two chunks")
        fun closeTagSplit() {
            val r1 = detector.process("hello<think/>reasoning</thi")
            val r2 = detector.process("nk/>world")
            assertThat(r1.content).isEqualTo("hello")
            assertThat(r1.reasoning).isEqualTo("reasoning")
            assertThat(r2.content).isEqualTo("world")
            assertThat(r2.reasoning).isEmpty()
        }

        @Test
        @DisplayName("entire think block delivered char by char")
        fun charByChar() {
            val text = "a<think/>b</think/>c"
            var allContent = ""
            var allReasoning = ""
            for (ch in text) {
                val r = detector.process(ch.toString())
                allContent += r.content
                allReasoning += r.reasoning
            }
            val final = detector.flush()
            allContent += final.content
            allReasoning += final.reasoning
            assertThat(allContent).isEqualTo("ac")
            assertThat(allReasoning).isEqualTo("b")
        }

        @Test
        @DisplayName("comment open tag split across chunks")
        fun commentOpenTagSplit() {
            val r1 = detector.process("hello<!-- THI")
            val r2 = detector.process("NKING_START -->thoughts<!-- THINKING_END -->world")
            assertThat(r1.content).isEqualTo("hello")
            assertThat(r1.reasoning).isEmpty()
            assertThat(r2.content).isEqualTo("world")
            assertThat(r2.reasoning).isEqualTo("thoughts")
        }

        @Test
        @DisplayName("reasoning content split across many chunks inside think block")
        fun reasoningAcrossChunks() {
            val r1 = detector.process("start<think/>")
            val r2 = detector.process("part1")
            val r3 = detector.process("part2")
            val r4 = detector.process("</think/>end")
            assertThat(r1.content).isEqualTo("start")
            assertThat(r1.reasoning).isEmpty()
            assertThat(r2.content).isEmpty()
            assertThat(r2.reasoning).isEqualTo("part1")
            assertThat(r3.content).isEmpty()
            assertThat(r3.reasoning).isEqualTo("part2")
            assertThat(r4.content).isEqualTo("end")
            assertThat(r4.reasoning).isEmpty()
        }

        @Test
        @DisplayName("bracket at end of chunk preserved in buffer")
        fun bracketAtEndPreserved() {
            val r1 = detector.process("some text<")
            assertThat(r1.content).isEqualTo("some text")
            assertThat(r1.reasoning).isEmpty()
            val r2 = detector.process("more text")
            assertThat(r2.content).isEmpty()
            assertThat(r2.reasoning).isEmpty()
            val r3 = detector.flush()
            assertThat(r3.content).isEqualTo("<more text")
            assertThat(r3.reasoning).isEmpty()
        }
    }

    @Nested
    @DisplayName("Flush behavior")
    inner class Flush {

        @Test
        @DisplayName("flush outputs remaining buffer as content when OUTSIDE")
        fun flushOutside() {
            detector.process("some content<")
            val result = detector.flush()
            assertThat(result.content).isEqualTo("<")
            assertThat(result.reasoning).isEmpty()
        }

        @Test
        @DisplayName("flush outputs remaining buffer as reasoning when INSIDE")
        fun flushInside() {
            val r1 = detector.process("before<think/>unfinished reasoning<")
            assertThat(r1.content).isEqualTo("before")
            assertThat(r1.reasoning).isEqualTo("unfinished reasoning")
            val result = detector.flush()
            assertThat(result.content).isEmpty()
            assertThat(result.reasoning).isEqualTo("<")
        }

        @Test
        @DisplayName("flush resets state")
        fun flushResets() {
            detector.process("before<think/>reasoning")
            detector.flush()
            val state = detector.getState()
            assertThat(state.state).isEqualTo("OUTSIDE")
            assertThat(state.bufferLength).isEqualTo(0)
        }

        @Test
        @DisplayName("flush after flush returns empty")
        fun doubleFlush() {
            detector.process("content")
            detector.flush()
            val result = detector.flush()
            assertThat(result.content).isEmpty()
            assertThat(result.reasoning).isEmpty()
        }
    }

    @Nested
    @DisplayName("Reset behavior")
    inner class Reset {

        @Test
        @DisplayName("reset clears all state")
        fun resetClears() {
            detector.process("before<think/>reasoning")
            detector.reset()
            val state = detector.getState()
            assertThat(state.state).isEqualTo("OUTSIDE")
            assertThat(state.bufferLength).isEqualTo(0)
        }

        @Test
        @DisplayName("process after reset starts fresh")
        fun processAfterReset() {
            detector.process("before<think/>reasoning")
            detector.reset()
            val result = detector.process("fresh content")
            assertThat(result.content).isEqualTo("fresh content")
            assertThat(result.reasoning).isEmpty()
        }
    }

    @Nested
    @DisplayName("Edge cases")
    inner class EdgeCases {

        @Test
        @DisplayName("empty think block")
        fun emptyThinkBlock() {
            val result = detector.process("before<think/></think/>after")
            assertThat(result.content).isEqualTo("beforeafter")
            assertThat(result.reasoning).isEmpty()
        }

        @Test
        @DisplayName("nested angle brackets in reasoning")
        fun angleBracketsInReasoning() {
            val result = detector.process("before<think/>a < b && c > d</think/>after")
            assertThat(result.content).isEqualTo("beforeafter")
            assertThat(result.reasoning).isEqualTo("a < b && c > d")
        }

        @Test
        @DisplayName("think tag with whitespace after name")
        fun thinkTagWithWhitespace() {
            val result = detector.process("before<think >reasoning</think >after")
            assertThat(result.content).isEqualTo("beforeafter")
            assertThat(result.reasoning).isEqualTo("reasoning")
        }

        @Test
        @DisplayName("close tag with extra whitespace")
        fun closeTagWithWhitespace() {
            val result = detector.process("before<think/>reasoning</think  >after")
            assertThat(result.content).isEqualTo("beforeafter")
            assertThat(result.reasoning).isEqualTo("reasoning")
        }

        @Test
        @DisplayName("mixed tag formats in one stream")
        fun mixedTagFormats() {
            val result = detector.process(
                "a<think/>b</think/>c<!-- THINKING_START -->d<!-- THINKING_END -->e<thought/>f</thought/>g"
            )
            assertThat(result.content).isEqualTo("aceg")
            assertThat(result.reasoning).isEqualTo("bdf")
        }

        @Test
        @DisplayName("very long reasoning content")
        fun longReasoning() {
            val reasoning = "x".repeat(10000)
            val result = detector.process("before<think/>${reasoning}</think/>after")
            assertThat(result.content).isEqualTo("beforeafter")
            assertThat(result.reasoning).isEqualTo(reasoning)
        }

        @Test
        @DisplayName("unclosed think block - process + flush combine correctly")
        fun unclosedThinkFlushedAsReasoning() {
            val r1 = detector.process("before<think/>partial reasoning")
            assertThat(r1.content).isEqualTo("before")
            assertThat(r1.reasoning).isEqualTo("partial reasoning")
            val flushed = detector.flush()
            assertThat(flushed.content).isEmpty()
            assertThat(flushed.reasoning).isEmpty()
        }

        @Test
        @DisplayName("only think block with no surrounding content")
        fun onlyThinkBlock() {
            val result = detector.process("<think/>just reasoning</think/>")
            assertThat(result.content).isEmpty()
            assertThat(result.reasoning).isEqualTo("just reasoning")
        }
    }

    @Nested
    @DisplayName("Channel-based processFlow")
    inner class ChannelProcessFlow {

        @Test
        @DisplayName("processes chunks via channels with backpressure")
        fun channelProcessing() = runBlocking {
            val input = Channel<String>(10)
            val output = Channel<ThinkingResult>(10)

            launch {
                detector.processFlow(input, output)
            }

            input.send("Hello ")
            input.send("<think/>reasoning")
            input.send("</think/>World")
            input.close()

            val results = mutableListOf<ThinkingResult>()
            withTimeout(2000) {
                for (r in output) {
                    results.add(r)
                }
            }

            val allContent = results.joinToString("") { it.content }
            val allReasoning = results.joinToString("") { it.reasoning }
            assertThat(allContent).isEqualTo("Hello World")
            assertThat(allReasoning).isEqualTo("reasoning")
        }

        @Test
        @DisplayName("channel flushes remaining content on close")
        fun channelFlushOnClose() = runBlocking {
            val input = Channel<String>(10)
            val output = Channel<ThinkingResult>(10)

            launch {
                detector.processFlow(input, output)
            }

            input.send("just content")
            input.close()

            val results = mutableListOf<ThinkingResult>()
            withTimeout(2000) {
                for (r in output) {
                    results.add(r)
                }
            }

            val allContent = results.joinToString("") { it.content }
            assertThat(allContent).isEqualTo("just content")
        }

        @Test
        @DisplayName("channel handles empty input gracefully")
        fun channelEmptyInput() = runBlocking {
            val input = Channel<String>(10)
            val output = Channel<ThinkingResult>(10)

            launch {
                detector.processFlow(input, output)
            }

            input.close()

            val results = mutableListOf<ThinkingResult>()
            withTimeout(2000) {
                for (r in output) {
                    results.add(r)
                }
            }

            assertThat(results).isEmpty()
        }
    }
}
