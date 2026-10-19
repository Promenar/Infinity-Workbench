package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 思考状态信封：可往返编码；旧版原始签名、损坏数据、未知字段名与缺签名块一律视为无可回传状态。 */
class ReasoningReplayCodecTest {
    @Test
    fun `两类状态可往返编码并保留来源模型`() {
        val blocks = ReasoningReplay.AnthropicBlocks(
            listOf(
                AnthropicThinkingBlock("thinking", "想", "sig"),
                AnthropicThinkingBlock("redacted_thinking", data = "enc"),
            ),
        )
        listOf(blocks, ReasoningReplay.ChatField("reasoning")).forEach { replay ->
            val decoded = ReasoningReplayCodec.decode(ReasoningReplayCodec.encode("deepseek::deepseek-v4", replay))!!
            assertThat(decoded.source).isEqualTo("deepseek::deepseek-v4")
            assertThat(decoded.replay).isEqualTo(replay)
        }
        assertThat(blocks.currentTurnOnly).isTrue()
        assertThat(ReasoningReplay.ChatField("reasoning_content").currentTurnOnly).isFalse()
    }

    @Test
    fun `旧数据与不合法内容解码为空`() {
        listOf(
            null,
            "",
            "CiQB0e2Kb-legacy-vertex-signature",
            "{not json",
            ReasoningReplayCodec.encode("", ReasoningReplay.ChatField("reasoning_content")),
            ReasoningReplayCodec.encode("p::m", ReasoningReplay.ChatField("tool_calls")),
            ReasoningReplayCodec.encode("p::m", ReasoningReplay.AnthropicBlocks(emptyList())),
            ReasoningReplayCodec.encode("p::m", ReasoningReplay.AnthropicBlocks(listOf(AnthropicThinkingBlock("thinking", "想")))),
            ReasoningReplayCodec.encode("p::m", ReasoningReplay.AnthropicBlocks(listOf(AnthropicThinkingBlock("text", "x", "sig")))),
            ReasoningReplayCodec.encode("p::m", ReasoningReplay.ChatField("reasoning_content")).replace("\"v\":1", "\"v\":9"),
        ).forEach { raw -> assertThat(ReasoningReplayCodec.decode(raw)).isNull() }
    }

    @Test
    fun `思考块日志不暴露正文与签名`() {
        assertThat(AnthropicThinkingBlock("thinking", "秘密推理", "sig-secret").toString()).doesNotContain("秘密")
        assertThat(AnthropicThinkingBlock("thinking", "秘密推理", "sig-secret").toString()).doesNotContain("sig-secret")
    }
}
