package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MarkdownTextTest {

    @Nested
    inner class NormalizeLatexDelimiters {

        @Test
        fun `converts block latex delimiters to double dollar block`() {
            val input = "\\[E = mc^2\\]"
            val result = normalizeLatexDelimiters(input)
            assertThat(result).isEqualTo("\$\$\nE = mc^2\n\$\$")
            assertThat(result).doesNotContain("\\[")
            assertThat(result).doesNotContain("\\]")
        }

        @Test
        fun `converts inline latex delimiters to single dollar`() {
            val input = "Inline \\(E = mc^2\\) here"
            val result = normalizeLatexDelimiters(input)
            assertThat(result).isEqualTo("Inline \$E = mc^2\$ here")
            assertThat(result).doesNotContain("\\(")
            assertThat(result).doesNotContain("\\)")
        }

        @Test
        fun `converts multi-line block latex content`() {
            val input = "\\[\nE = mc^2\nF = ma\n\\]"
            val result = normalizeLatexDelimiters(input)
            // group content is trimmed by implementation
            assertThat(result).isEqualTo("\$\$\nE = mc^2\nF = ma\n\$\$")
            assertThat(result).startsWith("\$\$\n")
            assertThat(result).endsWith("\n\$\$")
        }

        @Test
        fun `returns text unchanged when no latex delimiters present`() {
            val input = "Just some markdown **text** with no LaTeX."
            val result = normalizeLatexDelimiters(input)
            assertThat(result).isEqualTo(input)
        }

        @Test
        fun `converts mixed block and inline latex delimiters`() {
            val input = "Block \\[a + b\\] and inline \\(x = 1\\)."
            val result = normalizeLatexDelimiters(input)
            assertThat(result).isEqualTo("Block \$\$\na + b\n\$\$ and inline \$x = 1\$.")
        }

        @Test
        fun `trims surrounding whitespace inside block latex group`() {
            val input = "\\[   spaced formula   \\]"
            val result = normalizeLatexDelimiters(input)
            assertThat(result).isEqualTo("\$\$\nspaced formula\n\$\$")
        }
    }

    @Nested
    inner class SanitizeStreamingMarkdown {

        @Test
        fun `closes unclosed code fence when odd number of fences`() {
            val input = "```kotlin\nval x = 1"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result.endsWith("```")).isTrue()
            assertThat(result).isEqualTo("```kotlin\nval x = 1\n```")
        }

        @Test
        fun `does not modify already closed code fence`() {
            val input = "```kotlin\nval x = 1\n```"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo(input)
        }

        @Test
        fun `truncates from last unclosed double dollar block math`() {
            // Single $$ (odd) -> everything from the last $$ onward is dropped.
            val input = "intro\n\$\$\nE = mc^2"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo("intro\n")
            assertThat(result).doesNotContain("\$\$")
        }

        @Test
        fun `leaves balanced double dollar block math intact`() {
            val input = "\$\$\nE = mc^2\n\$\$"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo(input)
        }

        @Test
        fun `drops trailing lone single dollar when inline count is odd`() {
            // Lone trailing $ -> dropped (not closed by appending).
            val input = "cost is \$"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo("cost is ")
            assertThat(result).doesNotContain("\$")
        }

        @Test
        fun `does not append closing single dollar for non-trailing odd dollar`() {
            // Odd lone $ that is not at the end -> implementation leaves it untouched.
            val input = "price \$5 today"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo("price \$5 today")
        }

        @Test
        fun `returns already complete text unchanged`() {
            val input = "Hello **world** and `code`."
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo(input)
        }

        @Test
        fun `temporarily closes unbalanced bold marker while streaming`() {
            val input = "1. **预"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo("1. **预**")
        }

        @Test
        fun `ignores bold markers inside inline code and code fences`() {
            val input = "`**not bold**`\n```kotlin\nval text = \"**raw**\"\n```"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo(input)
        }

        @Test
        fun `returns empty string unchanged`() {
            val result = sanitizeStreamingMarkdown("")
            assertThat(result).isEqualTo("")
        }

        @Test
        fun `double dollar inside inline single dollar count is excluded`() {
            // $$ contributes zero to the lone-$ count; a trailing lone $ is still dropped.
            val input = "\$\$a\$\$ trailing \$"
            val result = sanitizeStreamingMarkdown(input)
            // $$ balanced (2) -> kept; lone trailing $ (count 1, odd, ends with $) -> dropped.
            assertThat(result).isEqualTo("\$\$a\$\$ trailing ")
        }
    }

    @Nested
    inner class SafeTrimIndent {

        @Test
        fun `trims common indent when no four-space indented lines`() {
            // 2-space indented lines do NOT trigger code-block protection, so trimIndent
            // collapses the shared 2-space prefix.
            val input = "  line one\n  line two"
            val result = safeTrimIndent(input)
            assertThat(result).isEqualTo("line one\nline two")
        }

        @Test
        fun `preserves text when a line starts with four-space indent`() {
            val input = "intro\n    val x = 1\n    val y = 2"
            val result = safeTrimIndent(input)
            assertThat(result).isEqualTo(input)
        }

        @Test
        fun `returns empty string for empty input`() {
            assertThat(safeTrimIndent("")).isEqualTo("")
        }

        @Test
        fun `returns single line unchanged when no shared indent`() {
            val input = "single line"
            val result = safeTrimIndent(input)
            assertThat(result).isEqualTo("single line")
        }

        @Test
        fun `does not treat four-space-only blank line as code block`() {
            // A line that is only whitespace after the 4 spaces does NOT trigger protection,
            // so trimIndent applies and trims the leading blank line.
            val result = safeTrimIndent("    \nrest")
            assertThat(result).isEqualTo("rest")
        }
    }

    @Nested
    inner class RepairCompressedMarkdownBoundaries {

        @Test
        fun `separates compressed cjk headings into block headings`() {
            val input = "前言 #一级标题##二级标题###三级标题正文"
            val result = repairCompressedMarkdownBoundaries(input)

            assertThat(result).contains("前言\n\n# 一级标题")
            assertThat(result).contains("\n\n## 二级标题")
            assertThat(result).contains("\n\n### 三级标题正文")
        }

        @Test
        fun `separates horizontal rule followed by heading`() {
            val input = "排版效果： ---###列表测试"
            val result = repairCompressedMarkdownBoundaries(input)

            assertThat(result).contains("排版效果：\n\n---\n\n### 列表测试")
        }

        @Test
        fun `separates compressed ordered list markers`() {
            val input = "有序列表： **1.第一步 2.第二步 3.第三步###代码块测试"
            val result = repairCompressedMarkdownBoundaries(input)

            assertThat(result).contains("有序列表：\n1. 第一步")
            assertThat(result).doesNotContain("**\n1.")
            assertThat(result).contains("\n2. 第二步")
            assertThat(result).contains("\n3. 第三步")
            assertThat(result).contains("\n\n### 代码块测试")
        }

        @Test
        fun `separates compressed markdown table rows`() {
            val input = "###表格测试|功能|状态|备注||---|---|---||粗体|✅|正常工作||斜体|✅|正常工作"
            val result = repairCompressedMarkdownBoundaries(input)

            assertThat(result).contains("### 表格测试\n|功能|状态|备注|")
            assertThat(result).contains("\n|---|---|---|")
            assertThat(result).contains("\n|粗体|✅|正常工作|")
            assertThat(result).contains("\n|斜体|✅|正常工作|")
        }

        @Test
        fun `does not alter inline code and links`() {
            val input = "保留 `#标签##值 1.不是列表` 和 [标题#一](https://example.com/a#b)"
            val result = repairCompressedMarkdownBoundaries(input)

            assertThat(result).isEqualTo(input)
        }

        @Test
        fun `does not trim protected indented code block`() {
            val input = "    val x = 1\n    val y = 2"
            val result = repairCompressedMarkdownBoundaries(input)

            assertThat(result).isEqualTo(input)
        }
    }

    @Nested
    inner class InsertCjkSpacing {

        @Test
        fun `inserts hair space between cjk and following ascii letter`() {
            val result = insertCjkSpacing("测试hello")
            assertThat(result).isEqualTo("测试\u200Ahello")
        }

        @Test
        fun `inserts hair space between ascii letter and following cjk`() {
            val result = insertCjkSpacing("hello测试")
            assertThat(result).isEqualTo("hello\u200A测试")
        }

        @Test
        fun `inserts hair space between cjk and digit boundary`() {
            // 函数在 CJK 与数字边界双向插入窄空格（共→3 与 3→项 都插）
            val result = insertCjkSpacing("共3项")
            assertThat(result).isEqualTo("共\u200A3\u200A项")
        }

        @Test
        fun `does not insert inside inline code spans`() {
            // Code containing CJK+ASCII adjacency must be preserved verbatim.
            val result = insertCjkSpacing("前`测a`后")
            assertThat(result).isEqualTo("前`测a`后")
            assertThat(result).doesNotContain("\u200A")
        }

        @Test
        fun `does not insert inside link text or url`() {
            val result = insertCjkSpacing("见[测试a](http://x.com)末")
            assertThat(result).isEqualTo("见[测试a](http://x.com)末")
            assertThat(result).doesNotContain("\u200A")
        }

        @Test
        fun `leaves pure english text unchanged`() {
            val input = "hello world foo bar"
            assertThat(insertCjkSpacing(input)).isEqualTo(input)
        }

        @Test
        fun `leaves pure cjk text unchanged`() {
            val input = "测试中文标点"
            assertThat(insertCjkSpacing(input)).isEqualTo(input)
        }

        @Test
        fun `inserts on both sides around mixed tokens`() {
            val result = insertCjkSpacing("中文ABC中文")
            assertThat(result).isEqualTo("中文\u200AABC\u200A中文")
        }
    }

    @Nested
    inner class StripBlockQuoteMarkers {

        @Test
        fun `strips single line block quote marker`() {
            val result = stripBlockQuoteMarkers("> 文本")
            assertThat(result).isEqualTo("文本")
        }

        @Test
        fun `strips multi line block quote`() {
            val input = "> line one\n> line two\n> line three"
            val result = stripBlockQuoteMarkers(input)
            assertThat(result).isEqualTo("line one\nline two\nline three")
        }

        @Test
        fun `reduces nested block quote by one level`() {
            val input = ">> nested"
            val result = stripBlockQuoteMarkers(input)
            assertThat(result).isEqualTo("> nested")
        }

        @Test
        fun `returns non quote lines verbatim`() {
            val input = "plain line\nanother line"
            val result = stripBlockQuoteMarkers(input)
            assertThat(result).isEqualTo("plain line\nanother line")
        }

        @Test
        fun `strips marker when no space follows the angle bracket`() {
            val result = stripBlockQuoteMarkers(">文本")
            assertThat(result).isEqualTo("文本")
        }

        @Test
        fun `trims leading whitespace before angle bracket`() {
            val result = stripBlockQuoteMarkers("   > indented quote")
            assertThat(result).isEqualTo("indented quote")
        }
    }

    @Nested
    inner class ReplaceCodeInMarkdown {

        @Test
        fun `replaces matching code block content`() {
            val markdown = "```kotlin\nval x = 1\n```"
            val result = replaceCodeInMarkdown(markdown, "kotlin", "val x = 1", "val y = 2")
            assertThat(result).isEqualTo("```kotlin\nval y = 2\n```")
        }

        @Test
        fun `replaces only the matching language among multiple blocks`() {
            val markdown = "```python\nprint(1)\n```\n```kotlin\nval x = 1\n```"
            val result = replaceCodeInMarkdown(markdown, "kotlin", "val x = 1", "val y = 2")
            assertThat(result).isEqualTo("```python\nprint(1)\n```\n```kotlin\nval y = 2\n```")
        }

        @Test
        fun `returns unchanged when language does not match`() {
            val markdown = "```kotlin\nval x = 1\n```"
            val result = replaceCodeInMarkdown(markdown, "java", "val x = 1", "val y = 2")
            assertThat(result).isEqualTo(markdown)
        }

        @Test
        fun `returns unchanged when no code block present`() {
            val markdown = "just plain text"
            val result = replaceCodeInMarkdown(markdown, "kotlin", "val x = 1", "val y = 2")
            assertThat(result).isEqualTo(markdown)
        }

        @Test
        fun `handles null language as empty fence label`() {
            val markdown = "```\ncode here\n```"
            val result = replaceCodeInMarkdown(markdown, null, "code here", "new code")
            assertThat(result).isEqualTo("```\nnew code\n```")
        }
    }

    @Nested
    inner class Slugify {

        @Test
        fun `slugifies plain english title`() {
            assertThat(slugify("Hello World")).isEqualTo("hello-world")
        }

        @Test
        fun `preserves cjk characters in slug`() {
            // \p{L} includes CJK ideographs, so Chinese is retained (only lowercased,
            // which is a no-op for CJK).
            assertThat(slugify("测试标题")).isEqualTo("测试标题")
        }

        @Test
        fun `removes punctuation from title`() {
            assertThat(slugify("Hello, World!")).isEqualTo("hello-world")
        }

        @Test
        fun `collapses multiple whitespace into single hyphen`() {
            assertThat(slugify("Multiple   spaces")).isEqualTo("multiple-spaces")
        }

        @Test
        fun `keeps alphanumeric mix`() {
            assertThat(slugify("Section 42")).isEqualTo("section-42")
        }

        @Test
        fun `returns fallback heading when result is empty`() {
            assertThat(slugify("!!!???")).isEqualTo("heading")
        }

        @Test
        fun `returns fallback heading for blank input`() {
            assertThat(slugify("   ")).isEqualTo("heading")
        }
    }

    @Nested
    inner class SplitRichSegments {

        @Test
        fun `returns single markdown segment for plain text`() {
            val result = splitRichSegments("just some markdown text")
            assertThat(result).hasSize(1)
            assertThat(result[0]).isInstanceOf(ContentSegment.Markdown::class.java)
            assertThat((result[0] as ContentSegment.Markdown).content).isEqualTo("just some markdown text")
        }

        @Test
        fun `splits out mermaid code block`() {
            val input = "```mermaid\ngraph TD\nA-->B\n```"
            val result = splitRichSegments(input)
            assertThat(result).hasSize(1)
            assertThat(result[0]).isInstanceOf(ContentSegment.Mermaid::class.java)
            assertThat((result[0] as ContentSegment.Mermaid).content).isEqualTo("graph TD\nA-->B")
        }

        @Test
        fun `splits out echarts code block`() {
            val input = "```echarts\n{\"x\":1}\n```"
            val result = splitRichSegments(input)
            assertThat(result).hasSize(1)
            assertThat(result[0]).isInstanceOf(ContentSegment.ECharts::class.java)
            assertThat((result[0] as ContentSegment.ECharts).content).isEqualTo("{\"x\":1}")
        }

        @Test
        fun `splits out plantuml code block`() {
            val input = "```plantuml\n@startuml\nA-->B\n@enduml\n```"
            val result = splitRichSegments(input)
            assertThat(result).hasSize(1)
            assertThat(result[0]).isInstanceOf(ContentSegment.PlantUml::class.java)
            assertThat((result[0] as ContentSegment.PlantUml).content)
                .isEqualTo("@startuml\nA-->B\n@enduml")
        }

        @Test
        fun `splits out block level latex`() {
            val input = "\$\$E = mc^2\$\$"
            val result = splitRichSegments(input)
            assertThat(result).hasSize(1)
            assertThat(result[0]).isInstanceOf(ContentSegment.Latex::class.java)
            assertThat((result[0] as ContentSegment.Latex).content).isEqualTo("E = mc^2")
        }

        @Test
        fun `splits out inline latex`() {
            val input = "math \$E = mc^2\$ here"
            val result = splitRichSegments(input)
            assertThat(result).hasSize(3)
            assertThat(result[0]).isInstanceOf(ContentSegment.Markdown::class.java)
            assertThat((result[0] as ContentSegment.Markdown).content).isEqualTo("math ")
            assertThat(result[1]).isInstanceOf(ContentSegment.InlineLatex::class.java)
            assertThat((result[1] as ContentSegment.InlineLatex).content).isEqualTo("E = mc^2")
            assertThat(result[2]).isInstanceOf(ContentSegment.Markdown::class.java)
            assertThat((result[2] as ContentSegment.Markdown).content).isEqualTo(" here")
        }

        @Test
        fun `preserves segment order and types for mixed content`() {
            val input = "intro\n```mermaid\ngraph TD\nA-->B\n```\nmiddle\n\$\$E = mc^2\$\$\nend"
            val result = splitRichSegments(input)
            assertThat(result).hasSize(5)
            assertThat(result[0]).isInstanceOf(ContentSegment.Markdown::class.java)
            assertThat((result[0] as ContentSegment.Markdown).content).isEqualTo("intro\n")
            assertThat(result[1]).isInstanceOf(ContentSegment.Mermaid::class.java)
            assertThat((result[1] as ContentSegment.Mermaid).content).isEqualTo("graph TD\nA-->B")
            assertThat(result[2]).isInstanceOf(ContentSegment.Markdown::class.java)
            assertThat((result[2] as ContentSegment.Markdown).content).isEqualTo("\nmiddle\n")
            assertThat(result[3]).isInstanceOf(ContentSegment.Latex::class.java)
            assertThat((result[3] as ContentSegment.Latex).content).isEqualTo("E = mc^2")
            assertThat(result[4]).isInstanceOf(ContentSegment.Markdown::class.java)
            assertThat((result[4] as ContentSegment.Markdown).content).isEqualTo("\nend")
        }

        @Test
        fun `treats language case insensitively for rich blocks`() {
            val input = "```MERMAID\ngraph TD\nA-->B\n```"
            val result = splitRichSegments(input)
            assertThat(result).hasSize(1)
            assertThat(result[0]).isInstanceOf(ContentSegment.Mermaid::class.java)
        }
    }

    @Nested
    inner class StandaloneInlineCode {

        private fun paragraph(markdown: String): ASTNode =
            MarkdownParser(GFMFlavourDescriptor())
                .buildMarkdownTreeFromString(markdown)
                .children
                .single { it.type == MarkdownElementTypes.PARAGRAPH }

        @Test
        fun `recognizes a paragraph whose AST has exactly one code span`() {
            val command = "./gradlew :app:validateDebugScreenshotTest --tests WIDE_CONTENT_END_SENTINEL"
            val markdown = "`$command`"

            val result = standaloneInlineCode(paragraph(markdown), markdown)

            assertThat(result).isEqualTo(command)
        }

        @Test
        fun `recognizes a multi backtick code span without handwritten delimiter parsing`() {
            val markdown = "`` code ` inside ``"

            val result = standaloneInlineCode(paragraph(markdown), markdown)

            assertThat(result).isEqualTo("code ` inside")
        }

        @Test
        fun `rejects paragraph when code span has prose sibling nodes`() {
            val markdown = "Run `./gradlew test` before publishing."

            val result = standaloneInlineCode(paragraph(markdown), markdown)

            assertThat(result).isNull()
        }

        @Test
        fun `rejects paragraph when it has multiple code span nodes`() {
            val markdown = "`./gradlew test` `./gradlew lint`"

            val result = standaloneInlineCode(paragraph(markdown), markdown)

            assertThat(result).isNull()
        }

        @Test
        fun `rejects non paragraph AST node even when it contains one code span`() {
            val markdown = "- `./gradlew test`"
            val list = MarkdownParser(GFMFlavourDescriptor())
                .buildMarkdownTreeFromString(markdown)
                .children
                .single()

            val result = standaloneInlineCode(list, markdown)

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class WideContentMeasurement {

        @Test
        fun `chooses widest measured logical line instead of longest string`() {
            val text = "iiiiiiiiiiiiiiii\nWWWW\n中文🚀"
            val measuredWidths = mapOf(
                "iiiiiiiiiiiiiiii" to 48,
                "WWWW" to 92,
                "中文🚀" to 76,
            )

            val width = widestLogicalLineWidthPx(text) { line -> measuredWidths.getValue(line) }

            assertThat(width).isEqualTo(92)
        }

        @Test
        fun `measures every logical line including empty and emoji lines`() {
            val measured = mutableListOf<String>()

            val width = widestLogicalLineWidthPx("first\n\n🚀") { line ->
                measured += line
                when (line) {
                    "first" -> 20
                    "" -> 0
                    else -> 44
                }
            }

            assertThat(measured).containsExactly("first", "", "🚀").inOrder()
            assertThat(width).isEqualTo(44)
        }
    }

    @Nested
    inner class NestedWideTableDetection {

        @Test
        fun `finds a GFM table nested inside a block quote`() {
            val markdown = """
                > | A | B | C | D | E | F |
                > |---|---|---|---|---|---|
                > | 1 | 2 | 3 | 4 | 5 | END |
            """.trimIndent()
            val blockQuote = MarkdownParser(GFMFlavourDescriptor())
                .buildMarkdownTreeFromString(markdown)
                .children
                .single()

            assertThat(blockQuote.containsWideTableDescendant()).isTrue()
        }

        @Test
        fun `finds a GFM table nested inside a list item`() {
            val markdown = """
                - item

                  | A | B | C | D | E | F |
                  |---|---|---|---|---|---|
                  | 1 | 2 | 3 | 4 | 5 | END |
            """.trimIndent()
            val list = MarkdownParser(GFMFlavourDescriptor())
                .buildMarkdownTreeFromString(markdown)
                .children
                .single()

            assertThat(list.containsWideTableDescendant()).isTrue()
        }

        @Test
        fun `ordinary quote and list do not enter the recursive wide renderer`() {
            val markdown = "> ordinary quote\n\n- ordinary list item"
            val roots = MarkdownParser(GFMFlavourDescriptor())
                .buildMarkdownTreeFromString(markdown)
                .children

            assertThat(roots).isNotEmpty()
            roots.forEach { node -> assertThat(node.containsWideTableDescendant()).isFalse() }
        }
    }
}
