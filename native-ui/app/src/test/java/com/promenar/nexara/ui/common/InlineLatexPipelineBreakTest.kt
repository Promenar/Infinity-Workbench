package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** 验证正文预处理与富文本分段能完整保留行内公式。 */
class InlineLatexPipelineBreakTest {

    private val full = """
        1. Kotlin 代码块

        ```kotlin
        fun helloWorld() {
            println("Hello, World!")
        }
        ```

        2. 行星表格

        | 行星 | 直径 (km) | 距日距离 (百万 km) |
        |------|----------|-------------------|
        | 地球 | 12,742   | 149.6             |
        | 火星 | 6,779    | 227.9             |

        3. 行内 LaTeX 二次方程求根公式：${'$'}x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}${'$'}

        4. Mermaid 流程图

        ```mermaid
        flowchart TD
            A[开始] --> B{是否完成?}
            B -- 是 --> C[结束]
        ```

        5. GFM 警告提示块

        > [!WARNING]
        > 请勿在生产环境直接执行该命令，可能会造成数据丢失！

        6. ECharts 简单柱状图

        ```echarts
        {"xAxis":{"data":["苹果","香蕉","橙子"]},"yAxis":{},"series":[{"type":"bar","data":[120,200,150]}]}
        ```
    """.trimIndent()

    private fun hasInlineLatex(text: String): Boolean =
        splitRichSegments(text).any { it is ContentSegment.InlineLatex }

    @Test
    fun `stage0 raw content segments inline latex`() {
        assertThat(hasInlineLatex(full)).isTrue()
    }

    @Test
    fun `stage1 normalize keeps inline latex`() {
        assertThat(hasInlineLatex(normalizeLatexDelimiters(full))).isTrue()
    }

    @Test
    fun `stage2 safeTrimIndent keeps inline latex`() {
        assertThat(hasInlineLatex(safeTrimIndent(normalizeLatexDelimiters(full)))).isTrue()
    }

    @Test
    fun `stage3 repairCompressed keeps inline latex`() {
        val before = normalizeLatexDelimiters(full)
        val text = repairCompressedMarkdownBoundaries(safeTrimIndent(before))
        assertThat(hasInlineLatex(text)).isTrue()
    }

    @Test
    fun `完整正文预处理保留行内公式`() {
        val text = prepareMarkdownForRendering(full)
        assertThat(hasInlineLatex(text)).isTrue()
    }

    @Test
    fun `stage5 full pipeline segments inline latex`() {
        val processed = prepareMarkdownForRendering(full)
        val segments = splitRichSegments(processed)
        val kinds = segments.map { it::class.simpleName }
        assertThat(kinds).contains("InlineLatex")
    }
}
