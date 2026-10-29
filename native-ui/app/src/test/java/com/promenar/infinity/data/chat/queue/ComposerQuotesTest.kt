package com.promenar.infinity.data.chat.queue

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.MessageQuote
import org.junit.Test

class ComposerQuotesTest {
    private fun draft(id: String, annotation: String = "", text: String = "引文$id", source: String = "m1") =
        ComposerQuoteDraft(id = id, sourceMessageId = source, text = text, annotation = annotation)

    @Test
    fun `新建引用卡去掉选区首尾空白，空选区或缺少来源时不建卡`() {
        assertThat(ComposerQuotes.draft("m1", "  一段话\n", id = "q")).isEqualTo(ComposerQuoteDraft("q", "m1", "一段话"))
        assertThat(ComposerQuotes.draft("m1", " \n ")).isNull()
        assertThat(ComposerQuotes.draft("", "一段话")).isNull()
    }

    @Test
    fun `只有写了批注的引用进入消息，批注首尾空白去掉且顺序不变`() {
        val drafts = listOf(draft("a", " 第一条 "), draft("b", "  "), draft("c", "第三条"))

        assertThat(ComposerQuotes.toMessageQuotes(drafts)).containsExactly(
            MessageQuote("m1", "引文a", "第一条"),
            MessageQuote("m1", "引文c", "第三条"),
        ).inOrder()
    }

    @Test
    fun `发送按钮可用条件：主输入框有内容或至少一张写了批注的引用卡`() {
        val unannotated = listOf(draft("a"))
        val annotated = listOf(draft("a"), draft("b", "批注"))

        assertThat(ComposerQuotes.hasSendableDraft("", hasImages = false, hasDocuments = false, quotes = emptyList())).isFalse()
        assertThat(ComposerQuotes.hasSendableDraft("  ", hasImages = false, hasDocuments = false, quotes = unannotated)).isFalse()
        assertThat(ComposerQuotes.hasSendableDraft("", hasImages = false, hasDocuments = false, quotes = annotated)).isTrue()
        assertThat(ComposerQuotes.hasSendableDraft("正文", hasImages = false, hasDocuments = false, quotes = unannotated)).isTrue()
        assertThat(ComposerQuotes.hasSendableDraft("", hasImages = true, hasDocuments = false, quotes = unannotated)).isTrue()
        assertThat(ComposerQuotes.hasSendableDraft("", hasImages = false, hasDocuments = true, quotes = emptyList())).isTrue()
    }

    @Test
    fun `超过两张才折叠，折叠文案按总数与已写批注数计数`() {
        val two = listOf(draft("a"), draft("b", "x"))
        val four = two + listOf(draft("c", " "), draft("d", "y"))

        assertThat(ComposerQuotes.collapsible(two)).isFalse()
        assertThat(ComposerQuotes.collapsible(two + draft("c"))).isTrue()
        assertThat(four.size).isEqualTo(4)
        assertThat(ComposerQuotes.annotatedCount(four)).isEqualTo(2)
    }

    @Test
    fun `已发引用回到输入区时还原批注，与现有引用卡按来源和引文去重`() {
        val restored = ComposerQuotes.fromMessageQuotes(
            listOf(MessageQuote("m1", "引文a", "旧批注"), MessageQuote("m2", "引文x", "另一条")),
        )
        val current = listOf(draft("a", "新批注"))

        val merged = ComposerQuotes.merge(restored, current)

        assertThat(merged.map { it.text to it.annotation }).containsExactly(
            "引文x" to "另一条",
            "引文a" to "新批注",
        ).inOrder()
        assertThat(merged.map { it.id }.toSet()).hasSize(2)
    }
}
