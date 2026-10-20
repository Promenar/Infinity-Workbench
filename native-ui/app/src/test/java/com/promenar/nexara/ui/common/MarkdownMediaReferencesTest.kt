package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Test

class MarkdownMediaReferencesTest {
    private val legacyRoots = listOf("/data/files/generated_images")

    private fun references(markdown: String): List<MarkdownMediaReference>? {
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
        val paragraph = root.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        return markdownMediaOnlyReferences(paragraph, markdown, legacyRoots)
    }

    @Test
    fun `独占段落的工作区图片由块级卡片接管`() {
        assertThat(references("![生成结果](/media/2026-10-20/gen-1432-a1b2c3.png)"))
            .containsExactly(MarkdownMediaReference("/media/2026-10-20/gen-1432-a1b2c3.png", "生成结果", isLink = false))
    }

    @Test
    fun `多个媒体引用与工作区链接以空白或换行分隔时整体接管`() {
        assertThat(references("![a](media/a.png) ![](/b.mp4)\n[报告](/docs/r.pdf)"))
            .containsExactly(
                MarkdownMediaReference("media/a.png", "a", isLink = false),
                MarkdownMediaReference("/b.mp4", null, isLink = false),
                MarkdownMediaReference("/docs/r.pdf", "报告", isLink = true),
            ).inOrder()
    }

    @Test
    fun `含文字、远程图片、旧生图或普通链接的段落保持原排版`() {
        assertThat(references("见下图 ![a](/media/a.png)")).isNull()
        assertThat(references("![a](https://example.com/a.png)")).isNull()
        assertThat(references("![a](/data/files/generated_images/a.png)")).isNull()
        assertThat(references("![a](/media/a.png) ![b](https://example.com/b.png)")).isNull()
        assertThat(references("[锚点](#第一节)")).isNull()
        assertThat(references("[邮件](mailto:a@example.com)")).isNull()
        assertThat(references("普通段落")).isNull()
    }
}
