package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HtmlExtractorTest {

    @Nested
    inner class ExtractText {

        @Test
        fun `extracts plain text from simple html`() {
            val html = "<html><body><p>Hello world</p></body></html>"
            assertThat(HtmlExtractor.extractText(html)).isEqualTo("Hello world")
        }

        @Test
        fun `returns empty string for empty body`() {
            val html = "<html><body></body></html>"
            assertThat(HtmlExtractor.extractText(html)).isEmpty()
        }

        @Test
        fun `handles empty input`() {
            assertThat(HtmlExtractor.extractText("")).isEmpty()
        }

        @Test
        fun `strips all html tags from complex document`() {
            val html = """<html><head><title>Title</title><style>body{}</style></head>
                <body><div><h1>Header</h1><p>Paragraph with <a href="#">link</a>
                and <strong>bold</strong> text.</p><ul><li>Item 1</li><li>Item 2</li></ul>
                </div></body></html>"""
            val result = HtmlExtractor.extractText(html)
            assertThat(result).doesNotContain("<")
            assertThat(result).doesNotContain(">")
            assertThat(result).contains("Header")
            assertThat(result).contains("Paragraph with")
            assertThat(result).contains("link")
            assertThat(result).contains("bold")
            assertThat(result).contains("Item 1")
            assertThat(result).contains("Item 2")
        }

        @Test
        fun `decodes nbsp entity to space`() {
            val html = "<html><body><p>A&nbsp;B</p></body></html>"
            val result = HtmlExtractor.extractText(html)
            assertThat(result).doesNotContain("&nbsp;")
            assertThat(result).contains("A")
            assertThat(result).contains("B")
        }

        @Test
        fun `collapses multiple whitespace in extracted text`() {
            val html = "<html><body><p>Multiple     spaces    here</p></body></html>"
            val result = HtmlExtractor.extractText(html)
            assertThat(result).doesNotContain("     ")
            assertThat(result).contains("Multiple")
            assertThat(result).contains("spaces")
            assertThat(result).contains("here")
        }
    }

    @Nested
    inner class ExtractTextWithStructure {

        @Test
        fun `removes script tags and content`() {
            val html = "<html><body><p>Before</p><script>alert('xss')</script><p>After</p></body></html>"
            val result = HtmlExtractor.extractTextWithStructure(html)
            assertThat(result).contains("Before")
            assertThat(result).contains("After")
            assertThat(result).doesNotContain("alert")
            assertThat(result).doesNotContain("xss")
        }

        @Test
        fun `removes style tags and content`() {
            val html = "<html><body><p>Content</p><style>body { color: red; }</style></body></html>"
            val result = HtmlExtractor.extractTextWithStructure(html)
            assertThat(result).contains("Content")
            assertThat(result).doesNotContain("color")
            assertThat(result).doesNotContain("red")
        }

        @Test
        fun `removes noscript tags`() {
            val html = "<html><body><p>Visible</p><noscript>Hidden</noscript></body></html>"
            val result = HtmlExtractor.extractTextWithStructure(html)
            assertThat(result).contains("Visible")
            assertThat(result).doesNotContain("Hidden")
        }

        @Test
        fun `preserves paragraph structure with newlines`() {
            val html = "<html><body><p>Para 1</p><p>Para 2</p><p>Para 3</p></body></html>"
            val result = HtmlExtractor.extractTextWithStructure(html)
            assertThat(result).contains("Para 1")
            assertThat(result).contains("Para 2")
            assertThat(result).contains("Para 3")
            assertThat(result.split("\n").filter { it.isNotBlank() }).hasSize(3)
        }

        @Test
        fun `handles heading tags`() {
            val html = "<html><body><h1>Title</h1><p>Content</p></body></html>"
            val result = HtmlExtractor.extractTextWithStructure(html)
            assertThat(result).contains("Title")
            assertThat(result).contains("Content")
        }

        @Test
        fun `handles br tags as newlines`() {
            val html = "<html><body><p>Line 1<br>Line 2<br>Line 3</p></body></html>"
            val result = HtmlExtractor.extractTextWithStructure(html)
            assertThat(result).contains("Line 1")
            assertThat(result).contains("Line 2")
            assertThat(result).contains("Line 3")
        }

        @Test
        fun `handles list items`() {
            val html = "<html><body><ul><li>Item 1</li><li>Item 2</li></ul></body></html>"
            val result = HtmlExtractor.extractTextWithStructure(html)
            assertThat(result).contains("Item 1")
            assertThat(result).contains("Item 2")
        }

        @Test
        fun `decodes html entities`() {
            val html = "<html><body><p>A &amp; B &lt; C &gt; D &quot;E&quot;</p></body></html>"
            val result = HtmlExtractor.extractTextWithStructure(html)
            assertThat(result).contains("A & B")
            assertThat(result).contains("B < C")
            assertThat(result).contains("C > D")
            assertThat(result).contains("\"E\"")
        }

        @Test
        fun `handles table rows`() {
            val html = "<html><body><table><tr><td>Cell 1</td></tr><tr><td>Cell 2</td></tr></table></body></html>"
            val result = HtmlExtractor.extractTextWithStructure(html)
            assertThat(result).contains("Cell 1")
            assertThat(result).contains("Cell 2")
        }

        @Test
        fun `decodes nbsp entity in structured extraction`() {
            val html = "<html><body><p>A&nbsp;B&nbsp;C</p></body></html>"
            val result = HtmlExtractor.extractTextWithStructure(html)
            assertThat(result).doesNotContain("&nbsp;")
            assertThat(result).contains("A")
            assertThat(result).contains("B")
            assertThat(result).contains("C")
        }

        @Test
        fun `collapses multiple blank lines`() {
            val html = "<html><body><p>A</p><p></p><p></p><p>B</p></body></html>"
            val result = HtmlExtractor.extractTextWithStructure(html)
            assertThat(result).doesNotContain("\n\n\n")
        }

        @Test
        fun `returns empty string for empty input`() {
            assertThat(HtmlExtractor.extractTextWithStructure("")).isEmpty()
        }

        @Test
        fun `handles deeply nested elements`() {
            val html = "<html><body><div><div><div><p>Deep content</p></div></div></div></body></html>"
            val result = HtmlExtractor.extractTextWithStructure(html)
            assertThat(result).contains("Deep content")
        }

        @ParameterizedTest
        @CsvSource(
            "<p>Hello</p>, Hello",
            "<a href='#'>Link text</a>, Link text"
        )
        fun `strips inline formatting tags`(html: String, expectedText: String) {
            val result = HtmlExtractor.extractTextWithStructure("<body>$html</body>")
            assertThat(result).contains(expectedText.trim())
        }
    }
}
