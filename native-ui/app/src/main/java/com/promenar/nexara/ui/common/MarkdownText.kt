package com.promenar.nexara.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.m3.Markdown
import com.promenar.nexara.ui.renderer.CodeBlockWithHeader
import com.promenar.nexara.ui.renderer.EChartsBlock
import com.promenar.nexara.ui.renderer.LatexBlock
import com.promenar.nexara.ui.renderer.MermaidBlock
import com.promenar.nexara.ui.renderer.nexaraMarkdownColors
import com.promenar.nexara.ui.renderer.nexaraMarkdownTypography
import com.promenar.nexara.ui.theme.NexaraColors

private sealed class ContentSegment {
    data class Markdown(val content: String) : ContentSegment()
    data class Latex(val content: String) : ContentSegment()
    data class Mermaid(val content: String) : ContentSegment()
    data class ECharts(val content: String) : ContentSegment()
}

private fun splitRichSegments(text: String): List<ContentSegment> {
    val blockPattern = Regex(
        """```(mermaid|echarts)\s*\n(.*?)```""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    val latexPattern = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)

    data class RichSpan(val start: Int, val end: Int, val segment: ContentSegment)

    val spans = mutableListOf<RichSpan>()

    for (m in blockPattern.findAll(text)) {
        val type = m.groupValues[1].lowercase()
        val content = m.groupValues[2].trim()
        val seg = when (type) {
            "mermaid" -> ContentSegment.Mermaid(content)
            "echarts" -> ContentSegment.ECharts(content)
            else -> continue
        }
        spans.add(RichSpan(m.range.first, m.range.last + 1, seg))
    }

    for (m in latexPattern.findAll(text)) {
        if (spans.none { it.start <= m.range.first && m.range.last < it.end }) {
            spans.add(RichSpan(m.range.first, m.range.last + 1,
                ContentSegment.Latex(m.groupValues[1].trim())))
        }
    }

    if (spans.isEmpty()) return listOf(ContentSegment.Markdown(text))

    spans.sortBy { it.start }

    val result = mutableListOf<ContentSegment>()
    var cursor = 0
    for (span in spans) {
        if (span.start > cursor) {
            result.add(ContentSegment.Markdown(text.substring(cursor, span.start)))
        }
        result.add(span.segment)
        cursor = span.end
    }
    if (cursor < text.length) {
        result.add(ContentSegment.Markdown(text.substring(cursor)))
    }
    return result
}

@Composable
fun MarkdownText(
    markdown: String,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    val processed = remember(markdown, isStreaming) {
        if (isStreaming) sanitizeStreamingMarkdown(markdown) else markdown
    }

    val segments = remember(processed) { splitRichSegments(processed) }

    Column(modifier = modifier.fillMaxWidth()) {
        for (segment in segments) {
            when (segment) {
                is ContentSegment.Markdown -> {
                    if (segment.content.isNotBlank()) {
                        Markdown(
                            content = segment.content,
                            colors = nexaraMarkdownColors(),
                            typography = nexaraMarkdownTypography(),
                            components = markdownComponents(
                                codeFence = { model ->
                                    MarkdownCodeFence(
                                        content = model.content,
                                        node = model.node,
                                        style = model.typography.code,
                                    ) { code, language, style ->
                                        CodeBlockWithHeader(code = code, language = language) {
                                            MarkdownHighlightedCode(
                                                code = code,
                                                language = language,
                                                style = style,
                                            )
                                        }
                                    }
                                },
                                codeBlock = { model ->
                                    MarkdownCodeBlock(
                                        content = model.content,
                                        node = model.node,
                                        style = model.typography.code,
                                    ) { code, language, style ->
                                        CodeBlockWithHeader(code = code, language = language) {
                                            MarkdownHighlightedCode(
                                                code = code,
                                                language = language,
                                                style = style,
                                            )
                                        }
                                    }
                                },
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                is ContentSegment.Latex -> {
                    LatexBlock(latex = segment.content)
                }
                is ContentSegment.Mermaid -> {
                    MermaidBlock(code = segment.content)
                }
                is ContentSegment.ECharts -> {
                    EChartsBlock(optionJson = segment.content)
                }
            }
        }

        if (isStreaming) {
            StreamingCursor()
        }
    }
}

private fun sanitizeStreamingMarkdown(text: String): String {
    var result = text

    val fenceCount = Regex("```").findAll(result).count()
    if (fenceCount % 2 != 0) {
        result += "\n```"
    }

    val latexCount = Regex("""\$\$""").findAll(result).count()
    if (latexCount % 2 != 0) {
        val lastIdx = result.lastIndexOf("$$")
        if (lastIdx >= 0) {
            result = result.substring(0, lastIdx)
        }
    }

    return result
}

@Composable
private fun StreamingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    Box(
        modifier = Modifier
            .padding(start = 4.dp, top = 4.dp)
            .width(8.dp)
            .height(16.dp)
            .alpha(alpha)
            .background(NexaraColors.Primary, RoundedCornerShape(1.dp))
    )
}
