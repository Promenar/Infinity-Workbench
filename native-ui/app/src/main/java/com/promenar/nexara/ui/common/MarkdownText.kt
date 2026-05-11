package com.promenar.nexara.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalImageTransformer
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.promenar.nexara.ui.renderer.CodeBlockWithHeader
import com.promenar.nexara.ui.renderer.EChartsBlock
import com.promenar.nexara.ui.renderer.InlineLatexSpan
import com.promenar.nexara.ui.renderer.PlantUmlBlock
import com.promenar.nexara.ui.renderer.ImageLightbox
import com.promenar.nexara.ui.renderer.LatexBlock
import com.promenar.nexara.ui.renderer.MermaidBlock
import com.promenar.nexara.ui.renderer.NexaraTableWidget
import com.promenar.nexara.ui.renderer.nexaraMarkdownColors
import com.promenar.nexara.ui.renderer.nexaraMarkdownTypography
import com.promenar.nexara.ui.theme.NexaraColors
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

private fun ASTNode.findLinkDestination(): ASTNode? {
    children.forEach { child ->
        if (child.type == MarkdownElementTypes.LINK_DESTINATION) return child
        child.findLinkDestination()?.let { return it }
    }
    return null
}

private sealed class ContentSegment {
    data class Markdown(val content: String) : ContentSegment()
    data class Latex(val content: String) : ContentSegment()
    data class InlineLatex(val content: String) : ContentSegment()
    data class Mermaid(val content: String) : ContentSegment()
    data class ECharts(val content: String) : ContentSegment()
    data class PlantUml(val content: String) : ContentSegment()
}

private class ParseCache {
    var text: String = ""
    var segments: List<ContentSegment> = emptyList()
}

private const val RE_PARSE_THRESHOLD = 100

private fun splitRichSegments(text: String): List<ContentSegment> {
    val blockPattern = Regex(
        """```(mermaid|echarts|plantuml)\s*\n(.*?)```""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    val latexPattern = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)
    val inlineLatexPattern = Regex("""(?<!\$)\$(?!\$)(.+?)(?<!\$)\$(?!\$)""")

    data class RichSpan(val start: Int, val end: Int, val segment: ContentSegment)

    val spans = mutableListOf<RichSpan>()

    for (m in blockPattern.findAll(text)) {
        val type = m.groupValues[1].lowercase()
        val content = m.groupValues[2].trim()
        val seg = when (type) {
            "mermaid" -> ContentSegment.Mermaid(content)
            "echarts" -> ContentSegment.ECharts(content)
            "plantuml" -> ContentSegment.PlantUml(content)
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

    for (m in inlineLatexPattern.findAll(text)) {
        if (spans.none { it.start <= m.range.first && m.range.last < it.end }) {
            spans.add(RichSpan(m.range.first, m.range.last + 1,
                ContentSegment.InlineLatex(m.groupValues[1].trim())))
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

    val cache = remember { ParseCache() }
    val segments = remember(processed) {
        if (cache.text.isNotEmpty()
            && processed.startsWith(cache.text)
            && processed.length - cache.text.length < RE_PARSE_THRESHOLD
        ) {
            val newPart = processed.substring(cache.text.length)
            cache.segments + ContentSegment.Markdown(newPart)
        } else {
            val result = splitRichSegments(processed)
            cache.text = processed
            cache.segments = result
            result
        }
    }

    val mergedSegments = remember(segments) {
        val result = mutableListOf<ContentSegment>()
        val pendingLatex = mutableListOf<String>()
        val pendingMermaid = mutableListOf<String>()
        val pendingECharts = mutableListOf<String>()
        val pendingPlantUml = mutableListOf<String>()

        fun flushPending() {
            if (pendingLatex.isNotEmpty()) {
                result.add(ContentSegment.Latex(pendingLatex.joinToString("\n\n")))
                pendingLatex.clear()
            }
            if (pendingMermaid.isNotEmpty()) {
                result.add(ContentSegment.Mermaid(pendingMermaid.joinToString("\n")))
                pendingMermaid.clear()
            }
            if (pendingECharts.isNotEmpty()) {
                pendingECharts.forEach { result.add(ContentSegment.ECharts(it)) }
                pendingECharts.clear()
            }
            if (pendingPlantUml.isNotEmpty()) {
                pendingPlantUml.forEach { result.add(ContentSegment.PlantUml(it)) }
                pendingPlantUml.clear()
            }
        }

        for (segment in segments) {
            when (segment) {
                is ContentSegment.Markdown -> {
                    flushPending()
                    result.add(segment)
                }
                is ContentSegment.InlineLatex -> {
                    flushPending()
                    result.add(segment)
                }
                is ContentSegment.Latex -> {
                    if (pendingMermaid.isNotEmpty() || pendingECharts.isNotEmpty() || pendingPlantUml.isNotEmpty()) flushPending()
                    pendingLatex.add(segment.content)
                }
                is ContentSegment.Mermaid -> {
                    if (pendingLatex.isNotEmpty() || pendingECharts.isNotEmpty() || pendingPlantUml.isNotEmpty()) flushPending()
                    pendingMermaid.add(segment.content)
                }
                is ContentSegment.ECharts -> {
                    if (pendingLatex.isNotEmpty() || pendingMermaid.isNotEmpty() || pendingPlantUml.isNotEmpty()) flushPending()
                    pendingECharts.add(segment.content)
                }
                is ContentSegment.PlantUml -> {
                    if (pendingLatex.isNotEmpty() || pendingMermaid.isNotEmpty() || pendingECharts.isNotEmpty()) flushPending()
                    pendingPlantUml.add(segment.content)
                }
            }
        }
        flushPending()
        result
    }

    Column(modifier = modifier.fillMaxWidth()) {
        for (segment in mergedSegments) {
            when (segment) {
                is ContentSegment.Markdown -> {
                    if (segment.content.isNotBlank()) {
                        Markdown(
                            content = segment.content,
                            colors = nexaraMarkdownColors(),
                            typography = nexaraMarkdownTypography(),
                            components = markdownComponents(
                                image = { model ->
                                    val link = model.node.findLinkDestination()
                                        ?.getUnescapedTextInNode(model.content)

                                    if (link != null) {
                                        var showLightbox by remember { mutableStateOf(false) }
                                        val imageData = LocalImageTransformer.current.transform(link)
                                        if (imageData != null) {
                                            Image(
                                                painter = imageData.painter,
                                                contentDescription = imageData.contentDescription,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { showLightbox = true },
                                                alignment = imageData.alignment,
                                                contentScale = imageData.contentScale,
                                                alpha = imageData.alpha,
                                                colorFilter = imageData.colorFilter,
                                            )
                                            if (showLightbox) {
                                                ImageLightbox(
                                                    imageUrl = link,
                                                    onDismiss = { showLightbox = false }
                                                )
                                            }
                                        }
                                    }
                                },
                                table = { model ->
                                    NexaraTableWidget(
                                        model = model,
                                        modifier = Modifier
                                            .padding(vertical = 8.dp)
                                            .fillMaxWidth()
                                    )
                                },
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
                                                modifier = Modifier.fillMaxWidth()
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
                                                modifier = Modifier.fillMaxWidth()
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
                is ContentSegment.InlineLatex -> {
                    InlineLatexSpan(latex = segment.content)
                }
                is ContentSegment.Mermaid -> {
                    MermaidBlock(code = segment.content)
                }
                is ContentSegment.ECharts -> {
                    EChartsBlock(optionJson = segment.content)
                }
                is ContentSegment.PlantUml -> {
                    PlantUmlBlock(code = segment.content)
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

    // Handle code block fences
    val fenceCount = Regex("```").findAll(result).count()
    if (fenceCount % 2 != 0) {
        result += "\n```"
    }

    // Handle incomplete bold/italic (optional, but prevents sudden style shifts)
    
    // Handle inline math $...$
    val inlineMathCount = Regex("""(?<!\$)\$(?!\$)""").findAll(result).count()
    if (inlineMathCount % 2 != 0) {
        // Only strip the last $ if it's likely an unclosed one at the very end
        if (result.endsWith("$")) {
            result = result.dropLast(1)
        }
    }

    // Handle block math $$...$$
    val blockMathCount = Regex("""\$\$""").findAll(result).count()
    if (blockMathCount % 2 != 0) {
        // If block math is open, we can't easily "close" it without knowing where it started
        // For streaming, we often just want to hide the unclosed block to avoid broken rendering
        val lastIdx = result.lastIndexOf("$$")
        if (lastIdx >= 0) {
            // Keep the text before the unclosed block math
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
            .semantics {
                contentDescription = "Generating response"
            }
    )
}
