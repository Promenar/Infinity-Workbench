package com.promenar.nexara.data.remote.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("StreamParser")
class StreamParserTest {

    private lateinit var parser: StreamParser

    @BeforeEach
    fun setUp() {
        parser = StreamParser()
    }

    @Nested
    @DisplayName("Plain text")
    inner class PlainText {

        @Test
        @DisplayName("single chunk returns content directly")
        fun singleChunk() {
            val result = parser.process("Hello, world!")
            assertThat(result.content).isEqualTo("Hello, world!")
            assertThat(result.toolCalls).isNull()
            assertThat(result.plan).isNull()
        }

        @Test
        @DisplayName("multiple chunks accumulate correctly")
        fun multipleChunks() {
            val r1 = parser.process("Hello")
            val r2 = parser.process(", ")
            val r3 = parser.process("world!")
            assertThat(r1.content + r2.content + r3.content).isEqualTo("Hello, world!")
        }

        @Test
        @DisplayName("empty chunk produces empty content")
        fun emptyChunk() {
            val result = parser.process("")
            assertThat(result.content).isEmpty()
        }
    }

    @Nested
    @DisplayName("Code fences")
    inner class CodeFences {

        @Test
        @DisplayName("complete code fence in single chunk")
        fun completeFence() {
            val result = parser.process("Here is code:\n```python\nprint('hi')\n```\nDone.")
            assertThat(result.content).isEqualTo("Here is code:\n```python\nprint('hi')\n```\nDone.")
        }

        @Test
        @DisplayName("code fence split across chunks")
        fun splitFence() {
            val r1 = parser.process("Here is code:\n```python\nprint(")
            val r2 = parser.process("'hello')\n```\nDone.")

            val combined = r1.content + r2.content
            assertThat(combined).contains("```python")
            assertThat(combined).contains("print('hello')")
            assertThat(combined).contains("```")
            assertThat(combined).contains("Done.")
        }

        @Test
        @DisplayName("tilde fence")
        fun tildeFence() {
            val result = parser.process("~~~javascript\nconsole.log('hi')\n~~~\nEnd")
            assertThat(result.content).contains("~~~javascript")
            assertThat(result.content).contains("console.log('hi')")
            assertThat(result.content).contains("~~~")
            assertThat(result.content).contains("End")
        }

        @Test
        @DisplayName("inline code")
        fun inlineCode() {
            val result = parser.process("Use `val x = 1` here.")
            assertThat(result.content).isEqualTo("Use `val x = 1` here.")
        }

        @Test
        @DisplayName("multiple code blocks")
        fun multipleCodeBlocks() {
            val input = "First:\n```\ncode1\n```\nSecond:\n```\ncode2\n```\nEnd"
            val result = parser.process(input)
            assertThat(result.content).isEqualTo(input)
        }
    }

    @Nested
    @DisplayName("Tool call XML blocks")
    inner class ToolCallXml {

        @Test
        @DisplayName("tool_code block with JSON array")
        fun toolCodeArray() {
            val input = """Before<tool_code>[{"function":{"name":"search","arguments":{"q":"test"}}}]</tool_code>After"""
            val result = parser.process(input)
            assertThat(result.content).isEqualTo("BeforeAfter")
            assertThat(result.toolCalls).hasSize(1)
            assertThat(result.toolCalls!![0].name).isEqualTo("search")
        }

        @Test
        @DisplayName("tool_calls block with single object")
        fun toolCallsSingleObject() {
            val input = """<tool_calls>{"function":{"name":"calc","arguments":{"expr":"1+1"}}}</tool_calls>"""
            val result = parser.process(input)
            assertThat(result.toolCalls).hasSize(1)
            assertThat(result.toolCalls!![0].name).isEqualTo("calc")
        }

        @Test
        @DisplayName("DeepSeek call tag")
        fun deepSeekCallTag() {
            val input = """<call tool="search">{"query":"hello"}</call>"""
            val result = parser.process(input)
            assertThat(result.toolCalls).hasSize(1)
            assertThat(result.toolCalls!![0].name).isEqualTo("search")
            assertThat(result.toolCalls!![0].arguments["query"]).isEqualTo("hello")
        }

        @Test
        @DisplayName("DeepSeek call tag with tool_input wrapper")
        fun deepSeekCallTagWithToolInput() {
            val input = """<call tool="analyze"><tool_input>{"data":"test"}</tool_input></call>"""
            val result = parser.process(input)
            assertThat(result.toolCalls).hasSize(1)
            assertThat(result.toolCalls!![0].name).isEqualTo("analyze")
            assertThat(result.toolCalls!![0].arguments["data"]).isEqualTo("test")
        }

        @Test
        @DisplayName("tool_call_xml with function_name and XML parameters")
        fun toolCallXmlWithFunctionNameXmlParams() {
            val input = """<tool_call_xml><function_name>get_weather</function_name><parameters><city>Beijing</city><unit>celsius</unit></parameters></tool_call_xml>"""
            val result = parser.process(input)
            assertThat(result.content).isEmpty()
            assertThat(result.toolCalls).hasSize(1)
            assertThat(result.toolCalls!![0].name).isEqualTo("get_weather")
            assertThat(result.toolCalls!![0].arguments["city"]).isEqualTo("Beijing")
            assertThat(result.toolCalls!![0].arguments["unit"]).isEqualTo("celsius")
        }

        @Test
        @DisplayName("tool_call_xml with function_name and JSON parameters")
        fun toolCallXmlWithFunctionNameJsonParams() {
            val input = """<tool_call_xml><function_name>get_weather</function_name><parameters>{"city":"Beijing"}</parameters></tool_call_xml>"""
            val result = parser.process(input)
            assertThat(result.content).isEmpty()
            assertThat(result.toolCalls).hasSize(1)
            assertThat(result.toolCalls!![0].name).isEqualTo("get_weather")
            assertThat(result.toolCalls!![0].arguments["city"]).isEqualTo("Beijing")
        }

        @Test
        @DisplayName("tool_call tag with function_name and XML parameters")
        fun toolCallTagWithFunctionName() {
            val input = """<tool_call_xml><function_name>get_weather</function_name><parameters><city>Beijing</city></parameters></tool_call_xml>"""
            val result = parser.process(input)
            assertThat(result.content).isEmpty()
            assertThat(result.toolCalls).hasSize(1)
            assertThat(result.toolCalls!![0].name).isEqualTo("get_weather")
            assertThat(result.toolCalls!![0].arguments["city"]).isEqualTo("Beijing")
        }

        @Test
        @DisplayName("tool_call split across chunks")
        fun toolCallSplitAcrossChunks() {
            val r1 = parser.process("Before<tool_code>")
            assertThat(r1.content).isEqualTo("Before")
            assertThat(r1.toolCalls).isNull()

            val r2 = parser.process("""[{"function":{"name":"search","arguments":{"q":"test"}}}]""")
            assertThat(r2.content).isEmpty()

            val r3 = parser.process("</tool_code>After")
            assertThat(r3.toolCalls).hasSize(1)
            assertThat(r3.content).isEqualTo("After")
        }
    }

    @Nested
    @DisplayName("Plan blocks")
    inner class PlanBlocks {

        @Test
        @DisplayName("JSON array plan")
        fun jsonArrayPlan() {
            val input = """<plan>[{"title":"Step 1"},{"title":"Step 2"}]</plan>"""
            val result = parser.process(input)
            assertThat(result.plan).hasSize(2)
            assertThat(result.plan!![0].title).isEqualTo("Step 1")
            assertThat(result.plan!![1].title).isEqualTo("Step 2")
        }

        @Test
        @DisplayName("JSON object plan with steps")
        fun jsonObjectPlan() {
            val input = """<plan>{"steps":[{"title":"Analyze","status":"pending"}]}</plan>"""
            val result = parser.process(input)
            assertThat(result.plan).hasSize(1)
            assertThat(result.plan!![0].title).isEqualTo("Analyze")
            assertThat(result.plan!![0].status).isEqualTo("pending")
        }

        @Test
        @DisplayName("plan with surrounding text")
        fun planWithText() {
            val input = "Before<plan>[{\"title\":\"Do X\"}]</plan>After"
            val result = parser.process(input)
            assertThat(result.content).isEqualTo("BeforeAfter")
            assertThat(result.plan).hasSize(1)
        }

        @Test
        @DisplayName("legacy line-by-line plan fallback")
        fun legacyPlanFallback() {
            val input = "<plan>1. First step\n2. Second step</plan>"
            val result = parser.process(input)
            assertThat(result.plan).hasSize(2)
            assertThat(result.plan!![0].title).isEqualTo("First step")
            assertThat(result.plan!![1].title).isEqualTo("Second step")
        }
    }

    @Nested
    @DisplayName("Mixed content")
    inner class MixedContent {

        @Test
        @DisplayName("text + code + tool call")
        fun textCodeTool() {
            val input = "Hello\n```python\ncode\n```\n<tool_code>[{\"function\":{\"name\":\"run\",\"arguments\":{}}}]</tool_code>End"
            val result = parser.process(input)
            assertThat(result.content).contains("Hello")
            assertThat(result.content).contains("```python")
            assertThat(result.content).contains("code")
            assertThat(result.content).contains("```")
            assertThat(result.content).contains("End")
            assertThat(result.toolCalls).hasSize(1)
        }

        @Test
        @DisplayName("code fence inside tool block is consumed (not leaked as content)")
        fun codeFenceInsideToolBlock() {
            val input = "<tool_code>{\"function\":{\"name\":\"exec\",\"arguments\":{\"code\":\"x=1\"}}}</tool_code>"
            val result = parser.process(input)
            assertThat(result.content).isEmpty()
            assertThat(result.toolCalls).hasSize(1)
        }
    }

    @Nested
    @DisplayName("getCleanContent")
    inner class GetCleanContent {

        @Test
        @DisplayName("removes tool XML blocks from raw content")
        fun removesToolBlocks() {
            val raw = "Hello <tool_code>{\"name\":\"test\"}</tool_code> World"
            val clean = parser.getCleanContent(raw)
            assertThat(clean).isEqualTo("Hello  World")
        }

        @Test
        @DisplayName("removes thinking blocks")
        fun removesThinkingBlocks() {
            val raw = "Hello <!-- THINKING_START -->some thought<!-- THINKING_END --> World"
            val clean = parser.getCleanContent(raw)
            assertThat(clean).isEqualTo("Hello  World")
        }

        @Test
        @DisplayName("removes plan blocks")
        fun removesPlanBlocks() {
            val raw = "Text<plan>[{\"title\":\"Step 1\"}]</plan>More"
            val clean = parser.getCleanContent(raw)
            assertThat(clean).isEqualTo("TextMore")
        }
    }

    @Nested
    @DisplayName("Reset")
    inner class Reset {

        @Test
        @DisplayName("reset clears parser state for reuse")
        fun resetClearsState() {
            parser.process("Before<tool_code>")
            parser.reset()
            val result = parser.process("Fresh start")
            assertThat(result.content).isEqualTo("Fresh start")
            assertThat(result.toolCalls).isNull()
        }
    }
}
