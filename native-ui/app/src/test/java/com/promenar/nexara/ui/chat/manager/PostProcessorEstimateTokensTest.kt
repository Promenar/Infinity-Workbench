package com.promenar.nexara.ui.chat.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审计缺陷 A：字符数/4 估算对中文低估 2–3 倍，改为 CJK 感知估算。 */
class PostProcessorEstimateTokensTest {

    @Test
    fun `empty text is zero`() {
        assertEquals(0, PostProcessor.estimateTokens(""))
    }

    @Test
    fun `single char still counts as one token`() {
        assertEquals(1, PostProcessor.estimateTokens("a"))
        assertEquals(1, PostProcessor.estimateTokens("测"))
    }

    @Test
    fun `english keeps four chars per token`() {
        assertEquals(300, PostProcessor.estimateTokens("a".repeat(1200)))
    }

    @Test
    fun `chinese uses about one and a half chars per token`() {
        assertEquals(80, PostProcessor.estimateTokens("测".repeat(120)))
    }

    @Test
    fun `mixed text counts segments separately`() {
        val text = "a".repeat(400) + "测".repeat(300)
        assertEquals(300, PostProcessor.estimateTokens(text))
    }

    @Test
    fun `cjk punctuation and fullwidth forms counted as cjk`() {
        val tokens = PostProcessor.estimateTokens("，。！？＂＃")
        assertTrue(tokens >= 3)
    }
}
