package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrigramTextSplitterTest {
    @Test
    fun `切块保留换行与中文句末标点，不把相邻句子粘在一起`() {
        val splitter = TrigramTextSplitter(chunkSize = 1000, chunkOverlap = 0)

        assertThat(splitter.splitText("User: Say OK\nAssistant: OK"))
            .containsExactly("User: Say OK\nAssistant: OK")
        assertThat(splitter.splitText("第一句。第二句！第三句？第四句；结尾"))
            .containsExactly("第一句。第二句！第三句？第四句；结尾")
    }

    @Test
    fun `按句子边界切块时每块以原句结尾，拼回后与原文一致`() {
        val splitter = TrigramTextSplitter(chunkSize = 8, chunkOverlap = 0)

        val chunks = splitter.splitText("甲甲甲。乙乙乙。丙丙丙。")

        assertThat(chunks).containsExactly("甲甲甲。乙乙乙。", "丙丙丙。").inOrder()
        assertThat(chunks.joinToString("")).isEqualTo("甲甲甲。乙乙乙。丙丙丙。")
    }

    @Test
    fun `连续分隔符与空行不产生空块`() {
        val splitter = TrigramTextSplitter(chunkSize = 1000, chunkOverlap = 0)

        assertThat(splitter.splitText("第一段。\n\n第二段")).containsExactly("第一段。\n\n第二段")
        assertThat(splitter.splitText("\n。\n")).isEmpty()
    }
}
