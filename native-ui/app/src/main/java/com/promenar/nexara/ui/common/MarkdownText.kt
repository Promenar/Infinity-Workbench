package com.promenar.nexara.ui.common

import android.util.Log

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.LocalImageTransformer
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBlockQuote
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.compose.elements.MarkdownText as MarkdownElementText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.promenar.nexara.ui.renderer.CodeBlockWithHeader
import com.promenar.nexara.R
import com.promenar.nexara.ui.renderer.EChartsBlock
import com.promenar.nexara.ui.renderer.GfmAlertBlock
import com.promenar.nexara.ui.renderer.ImageLightbox
import com.promenar.nexara.ui.renderer.InlineLatexSpan
import com.promenar.nexara.ui.renderer.LatexBlock
import com.promenar.nexara.ui.renderer.MermaidBlock
import com.promenar.nexara.ui.renderer.NexaraTableWidget
import com.promenar.nexara.ui.renderer.PlantUmlBlock
import com.promenar.nexara.ui.renderer.nexaraMarkdownColors
import com.promenar.nexara.ui.renderer.nexaraMarkdownTypography
import com.promenar.nexara.ui.renderer.parseMarkdownTable
import com.promenar.nexara.ui.renderer.parseGfmAlert
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.testing.UiTags
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

private fun ASTNode.findLinkDestination(): ASTNode? {
    children.forEach { child ->
        if (child.type == MarkdownElementTypes.LINK_DESTINATION) return child
        child.findLinkDestination()?.let { return it }
    }
    return null
}

internal sealed class ContentSegment {
    data class Markdown(val content: String) : ContentSegment()
    data class Latex(val content: String) : ContentSegment()
    data class InlineLatex(val content: String) : ContentSegment()
    data class Mermaid(val content: String) : ContentSegment()
    data class ECharts(val content: String) : ContentSegment()
    data class PlantUml(val content: String) : ContentSegment()
}

internal class ParseCache {
    var text: String = ""
    var segments: List<ContentSegment> = emptyList()
}

internal const val STREAM_TAIL_FADE_DURATION_MS = 140

internal fun shouldAnimateStreamingTail(
    isStreaming: Boolean,
    inspectionMode: Boolean,
): Boolean = isStreaming && !inspectionMode

internal fun streamTailFadeStartAlpha(
    isStreaming: Boolean,
    previousContent: String,
    content: String,
    currentAlpha: Float,
): Float {
    if (
        !isStreaming ||
        content.isEmpty() ||
        content.length <= previousContent.length ||
        !content.startsWith(previousContent)
    ) {
        return 1f
    }
    return if (previousContent.isEmpty()) 0.68f else maxOf(0.68f, currentAlpha - 0.12f)
}

@Composable
private fun Modifier.streamingTailFade(
    enabled: Boolean,
    content: String,
): Modifier {
    if (!enabled) return this

    val maskColor = MaterialTheme.colorScheme.onSurface
    val tailAlpha = remember { Animatable(1f) }
    var previousContent by remember { mutableStateOf("") }
    LaunchedEffect(enabled, content) {
        val startAlpha = streamTailFadeStartAlpha(
            isStreaming = enabled,
            previousContent = previousContent,
            content = content,
            currentAlpha = tailAlpha.value,
        )
        previousContent = content
        tailAlpha.snapTo(startAlpha)
        if (startAlpha < 1f) {
            tailAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = STREAM_TAIL_FADE_DURATION_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
        }
    }
    return graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithContent {
        drawContent()
        val fadeHeight = 28.dp.toPx().coerceAtMost(size.height)
        if (fadeHeight > 0f && tailAlpha.value < 1f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(maskColor, maskColor.copy(alpha = tailAlpha.value)),
                    startY = size.height - fadeHeight,
                    endY = size.height,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

/** 仅接管 AST 已确认是“单个 CODE_SPAN 子节点”的顶层段落，不自行重写 CommonMark 分隔符规则。 */
internal fun standaloneInlineCode(node: ASTNode, content: String): String? {
    if (node.type != MarkdownElementTypes.PARAGRAPH) return null
    val codeSpan = node.children.singleOrNull()
        ?.takeIf { it.type == MarkdownElementTypes.CODE_SPAN }
        ?: return null
    val opening = codeSpan.children.firstOrNull()
        ?.takeIf { it.type == MarkdownTokenTypes.BACKTICK }
        ?: return null
    val closing = codeSpan.children.lastOrNull()
        ?.takeIf { it.type == MarkdownTokenTypes.BACKTICK }
        ?: return null
    if (opening.endOffset > closing.startOffset) return null

    val rawCode = content.substring(opening.endOffset, closing.startOffset)
        .replace("\r\n", " ")
        .replace('\r', ' ')
        .replace('\n', ' ')
    return if (
        rawCode.length >= 2 &&
        rawCode.first() == ' ' &&
        rawCode.last() == ' ' &&
        rawCode.any { it != ' ' }
    ) {
        rawCode.substring(1, rawCode.lastIndex)
    } else {
        rawCode
    }
}

internal fun widestLogicalLineWidthPx(
    text: String,
    measureLine: (String) -> Int,
): Int = text.lineSequence().maxOfOrNull(measureLine) ?: 0

internal fun ASTNode.containsWideTableDescendant(): Boolean =
    type == GFMElementTypes.TABLE || children.any(ASTNode::containsWideTableDescendant)

private fun nestedTableMinimumWidth(
    content: String,
    root: ASTNode,
    chromePerLevel: androidx.compose.ui.unit.Dp = 48.dp,
): androidx.compose.ui.unit.Dp? {
    fun visit(node: ASTNode, depth: Int): androidx.compose.ui.unit.Dp? {
        if (node.type == GFMElementTypes.TABLE) {
            return parseMarkdownTable(content, node)?.requiredWidth?.plus(chromePerLevel * depth)
        }
        return node.children.mapNotNull { child -> visit(child, depth + 1) }.maxOrNull()
    }

    return visit(root, depth = 0)
}

private const val RE_PARSE_THRESHOLD = 100

internal fun stripBlockQuoteMarkers(text: String): String {
    return text.lineSequence()
        .map { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith(">")) {
                trimmed.removePrefix(">").let { if (it.startsWith(" ")) it.drop(1) else it }
            } else {
                line
            }
        }
        .joinToString("\n")
        .trim()
}

internal fun splitRichSegments(text: String): List<ContentSegment> {
    val blockPattern = Regex(
        """(?s)```(mermaid|echarts|plantuml)\s*\n(.*?)\n```""",
        RegexOption.IGNORE_CASE
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

internal fun replaceCodeInMarkdown(
    markdown: String,
    language: String?,
    oldCode: String,
    newCode: String
): String {
    val lang = language ?: ""
    val oldFence = "```$lang\n$oldCode\n```"
    val newFence = "```$lang\n$newCode\n```"
    val idx = markdown.indexOf(oldFence)
    if (idx >= 0) {
        return markdown.substring(0, idx) + newFence + markdown.substring(idx + oldFence.length)
    }
    val oldFenceAlt = "```${lang.trim()}\n$oldCode\n```"
    val idxAlt = markdown.indexOf(oldFenceAlt)
    if (idxAlt >= 0) {
        return markdown.substring(0, idxAlt) + newFence + markdown.substring(idxAlt + oldFenceAlt.length)
    }
    return markdown
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    showCursor: Boolean = true,
    fontSize: Int = 13,
    smoothingCps: Int = StreamSpeed.BALANCED.cps,
    overrideColor: androidx.compose.ui.graphics.Color? = null,
    onContentChange: ((String) -> Unit)? = null,
    fontStyle: androidx.compose.ui.text.font.FontStyle? = null,
    compactSpacing: Boolean = false
) {
    val animateStreamingTail = shouldAnimateStreamingTail(
        isStreaming = isStreaming,
        inspectionMode = LocalInspectionMode.current,
    )
    val processed = remember(markdown, isStreaming) {
        val normalized = normalizeLatexDelimiters(markdown)
        val raw = if (isStreaming) sanitizeStreamingMarkdown(normalized) else normalized
        val safeTrimmed = safeTrimIndent(raw)
        val repaired = repairCompressedMarkdownBoundaries(safeTrimmed)
        insertCjkSpacing(repaired)
    }

    val smoothed = rememberSmoothStreamContent(
        content = processed,
        isStreaming = isStreaming,
        cps = if (isStreaming) smoothingCps else Int.MAX_VALUE
    )

    val cache = remember { ParseCache() }
    val segments = remember(smoothed) {
        if (cache.text.isNotEmpty()
            && smoothed.startsWith(cache.text)
            && smoothed.length - cache.text.length < RE_PARSE_THRESHOLD
        ) {
            val newPart = smoothed.substring(cache.text.length)
            val hasBoundaryCross = newPart.contains("```") || newPart.contains("$$")
            if (hasBoundaryCross) {
                val result = splitRichSegments(smoothed)
                cache.text = smoothed
                cache.segments = result
                result
            } else {
                // Merge with last segment if it's Markdown to avoid component explosion
                val last = cache.segments.lastOrNull()
                if (last is ContentSegment.Markdown) {
                    val updatedLast = ContentSegment.Markdown(last.content + newPart)
                    val newSegments = cache.segments.dropLast(1) + updatedLast
                    cache.text = smoothed
                    cache.segments = newSegments
                    newSegments
                } else {
                    val newSegments = cache.segments + ContentSegment.Markdown(newPart)
                    cache.text = smoothed
                    cache.segments = newSegments
                    newSegments
                }
            }
        } else {
            val result = splitRichSegments(smoothed)
            cache.text = smoothed
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
                    if (pendingLatex.isNotEmpty() || pendingMermaid.isNotEmpty() || pendingECharts.isNotEmpty() || pendingPlantUml.isNotEmpty()) flushPending()
                    result.add(segment)
                }
                is ContentSegment.Latex -> {
                    if (pendingMermaid.isNotEmpty() || pendingECharts.isNotEmpty() || pendingPlantUml.isNotEmpty()) flushPending()
                    pendingLatex.add(segment.content)
                }
                is ContentSegment.InlineLatex -> {
                    if (pendingLatex.isNotEmpty() || pendingMermaid.isNotEmpty() || pendingECharts.isNotEmpty() || pendingPlantUml.isNotEmpty()) flushPending()
                    result.add(segment)
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

    val currentStyle = LocalTextStyle.current
    val effectiveColor = overrideColor
        ?: currentStyle.color.takeUnless { it == Color.Unspecified }
        ?: MaterialTheme.colorScheme.onBackground
    val m3Typography = MaterialTheme.typography.copy(
        bodyMedium = nexaraMarkdownTypography(fontSize).text.copy(color = effectiveColor, fontStyle = fontStyle),
        headlineLarge = nexaraMarkdownTypography(fontSize).h1.copy(color = effectiveColor, fontStyle = fontStyle),
        headlineMedium = nexaraMarkdownTypography(fontSize).h2.copy(color = effectiveColor, fontStyle = fontStyle),
        headlineSmall = nexaraMarkdownTypography(fontSize).h3.copy(color = effectiveColor, fontStyle = fontStyle),
        titleLarge = nexaraMarkdownTypography(fontSize).h1.copy(color = effectiveColor, fontStyle = fontStyle),
        titleMedium = nexaraMarkdownTypography(fontSize).h2.copy(color = effectiveColor, fontStyle = fontStyle),
        titleSmall = nexaraMarkdownTypography(fontSize).h3.copy(color = effectiveColor, fontStyle = fontStyle),
        bodySmall = nexaraMarkdownTypography(fontSize).code.copy(color = effectiveColor, fontStyle = fontStyle),
        labelSmall = NexaraTypography.labelSmall.copy(
            fontSize = (fontSize - 2).coerceAtLeast(9).sp,
            color = effectiveColor.copy(alpha = 0.7f),
            fontStyle = fontStyle
        )
    )

    MaterialTheme(typography = m3Typography) {
        CompositionLocalProvider(
            LocalTextStyle provides m3Typography.bodyMedium,
            androidx.compose.material3.LocalContentColor provides effectiveColor,
            LocalImageTransformer provides Coil3ImageTransformerImpl
        ) {
            Column(modifier = modifier.fillMaxWidth()) {
                for ((index, segment) in mergedSegments.withIndex()) {
                    when (segment) {
                        is ContentSegment.Markdown -> {
                            if (segment.content.isNotBlank()) {
                                MarkdownSafe(
                                    content = segment.content,
                                    fontSize = fontSize,
                                    markdown = markdown,
                                    onContentChange = onContentChange,
                                    textColor = effectiveColor,
                                    fontStyle = fontStyle,
                                    compactSpacing = compactSpacing,
                                    modifier = Modifier.streamingTailFade(
                                        enabled = animateStreamingTail && index == mergedSegments.lastIndex,
                                        content = segment.content,
                                    ),
                                )
                            }
                        }
                        is ContentSegment.Latex -> {
                            LatexBlock(latex = segment.content, fontSize = fontSize)
                        }
                        is ContentSegment.InlineLatex -> {
                            InlineLatexSpan(latex = segment.content, fontSize = fontSize)
                        }
                        is ContentSegment.Mermaid -> {
                            MermaidBlock(code = segment.content, fontSize = fontSize)
                        }
                        is ContentSegment.ECharts -> {
                            EChartsBlock(optionJson = segment.content, fontSize = fontSize)
                        }
                        is ContentSegment.PlantUml -> {
                            PlantUmlBlock(code = segment.content, fontSize = fontSize)
                        }
                    }
                }

                if (isStreaming && showCursor) {
                    StreamingCursor()
                }
            }
        }
    }
}

internal fun safeTrimIndent(text: String): String {
    if (text.lines().any { it.startsWith("    ") && it.trimStart().isNotEmpty() }) {
        return text
    }
    return text.trimIndent()
}

internal fun repairCompressedMarkdownBoundaries(text: String): String {
    if (text.isBlank()) return text

    val protectedMap = mutableMapOf<String, String>()
    var counter = 0

    fun protect(pattern: Regex, input: String): String =
        pattern.replace(input) { match ->
            val key = "\u0000MD${counter++}\u0000"
            protectedMap[key] = match.value
            key
        }

    var processed = text
    processed = protect(Regex("""`[^`]*`"""), processed)
    processed = protect(Regex("""\[[^\]]+]\([^)]+\)"""), processed)

    processed = processed.replace(Regex("""[ \t]+---(?=#{1,6}(?!#))""")) {
        "\n\n---\n\n"
    }

    processed = processed.replace(Regex("""(?m)(^|[^\S\n])\*\*(?=\d{1,2}\.[^\s])""")) { match ->
        match.groupValues[1]
    }

    processed = processed.replace(Regex("""(?m)(^|(?<=[^\nA-Za-z0-9#]))[ \t]*(#{1,6})(?!#)[ \t]*([^\s#])""")) { match ->
        val needsBreak = match.range.first > 0 && processed[match.range.first - 1] != '\n'
        val prefix = if (needsBreak) "\n\n" else ""
        "$prefix${match.groupValues[2]} ${match.groupValues[3]}"
    }

    processed = processed.replace(Regex("""(?m)(^|(?<=[^\nA-Za-z0-9]))[ \t]*(\d{1,2})\.[ \t]*([^\s])""")) { match ->
        val needsBreak = match.range.first > 0 && processed[match.range.first - 1] != '\n'
        val prefix = if (needsBreak) "\n" else ""
        "$prefix${match.groupValues[2]}. ${match.groupValues[3]}"
    }

    processed = processed.replace(Regex("""(?m)(?<!\n)[ \t]+([-*+])[ \t]+(?=\S)""")) { match ->
        "\n${match.groupValues[1]} "
    }

    processed = processed.replace(Regex("""```(javascript|js|typescript|ts|kotlin|java|python|swift|go|rust|json|html|css)(?=(function|fun|class|const|let|var|import|def|public|private|val|return|\{|\[))""")) { match ->
        "```${match.groupValues[1]}\n"
    }

    processed = processed.replace(Regex("""(#{1,6} [^|\n]+)(\|)""")) { match ->
        "${match.groupValues[1]}\n${match.groupValues[2]}"
    }
    if (processed.contains(Regex("""\|\|[-:\s]*\|""")) || processed.contains(Regex("""\|\|[^|\n]+\|"""))) {
        processed = processed.replace("||", "|\n|")
        processed = processed.lines().joinToString("\n") { line ->
            if (line.startsWith("|") && line.count { it == '|' } >= 2 && !line.endsWith("|")) {
                "$line|"
            } else {
                line
            }
        }
    }

    processed = processed.replace(Regex("""(?m)(?<![\n<>=-])[ \t]*(>)[ \t]*([^\s])""")) { match ->
        val prefix = if (match.range.first > 0) "\n" else ""
        "$prefix> ${match.groupValues[2]}"
    }

    for ((key, value) in protectedMap) {
        processed = processed.replace(key, value)
    }

    return processed
}

@Composable
private fun MarkdownSafe(
    content: String,
    fontSize: Int,
    markdown: String,
    onContentChange: ((String) -> Unit)?,
    textColor: Color = MaterialTheme.colorScheme.onBackground,
    fontStyle: androidx.compose.ui.text.font.FontStyle? = null,
    compactSpacing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var renderError by remember(content) { mutableStateOf(false) }
    // 使用 rememberUpdatedState 避免回调变化导致 components 重建
    val currentMarkdown = rememberUpdatedState(markdown)
    val currentOnContentChange = rememberUpdatedState(onContentChange)

    if (renderError) {
        Text(
            text = content,
            modifier = modifier.fillMaxWidth(),
            style = LocalTextStyle.current
        )
        return
    }

    val components = remember(fontSize, fontStyle) {
        markdownComponents(
            heading1 = anchoredHeading({ it.typography.h1 }, MarkdownTokenTypes.ATX_CONTENT),
            heading2 = anchoredHeading({ it.typography.h2 }, MarkdownTokenTypes.ATX_CONTENT),
            heading3 = anchoredHeading({ it.typography.h3 }, MarkdownTokenTypes.ATX_CONTENT),
            heading4 = anchoredHeading({ it.typography.h4 }, MarkdownTokenTypes.ATX_CONTENT),
            heading5 = anchoredHeading({ it.typography.h5 }, MarkdownTokenTypes.ATX_CONTENT),
            heading6 = anchoredHeading({ it.typography.h6 }, MarkdownTokenTypes.ATX_CONTENT),
            setextHeading1 = anchoredHeading({ it.typography.h1 }, MarkdownTokenTypes.SETEXT_CONTENT),
            setextHeading2 = anchoredHeading({ it.typography.h2 }, MarkdownTokenTypes.SETEXT_CONTENT),
            blockQuote = { model ->
                val rawText = model.node.getUnescapedTextInNode(model.content)
                val stripped = stripBlockQuoteMarkers(rawText)
                if (parseGfmAlert(stripped) != null) {
                    GfmAlertBlock(
                        quoteContent = stripped,
                        fontSize = fontSize
                    )
                } else {
                    MarkdownBlockQuote(
                        content = model.content,
                        node = model.node,
                        style = model.typography.quote,
                    )
                }
            },
            image = { model ->
                val link = model.node.findLinkDestination()
                    ?.getUnescapedTextInNode(model.content)

                if (link != null) {
                    var showLightbox by remember(link) { mutableStateOf(false) }
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
                    fontSize = fontSize,
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
                    CodeBlockWithHeader(
                        code = code,
                        language = language,
                        fontSize = fontSize,
                        onCodeChange = if (currentOnContentChange.value != null) {
                            { newCode ->
                                currentOnContentChange.value?.invoke(
                                    replaceCodeInMarkdown(currentMarkdown.value, language, code, newCode)
                                )
                            }
                        } else null
                    ) {
                        MarkdownHighlightedCode(
                            code = code,
                            language = language,
                            style = style
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
                    CodeBlockWithHeader(
                        code = code,
                        language = language,
                        fontSize = fontSize,
                        onCodeChange = if (currentOnContentChange.value != null) {
                            { newCode ->
                                currentOnContentChange.value?.invoke(
                                    replaceCodeInMarkdown(currentMarkdown.value, language, code, newCode)
                                )
                            }
                        } else null
                    ) {
                        MarkdownHighlightedCode(
                            code = code,
                            language = language,
                            style = style
                        )
                    }
                }
            },
            horizontalRule = { _ ->
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            },
            custom = { type, model ->
                when (type) {
                    MarkdownElementTypes.HTML_BLOCK -> {
                        MarkdownElementText(
                            content = model.content,
                            node = model.node,
                            style = model.typography.text
                        )
                    }
                }
            }
        )
    }

    Markdown(
        content = content,
        annotator = markdownAnnotator(
            config = markdownAnnotatorConfig(eolAsNewLine = true),
        ),
        padding = markdownPadding(
            block = if (compactSpacing) 3.dp else 8.dp,
            list = if (compactSpacing) 0.dp else 8.dp,
            listItemTop = if (compactSpacing) 1.dp else 4.dp,
            listItemBottom = if (compactSpacing) 1.dp else 4.dp,
            listIndent = if (compactSpacing) 8.dp else 12.dp,
        ),
        colors = nexaraMarkdownColors(textColor = textColor),
        typography = nexaraMarkdownTypography(fontSize, fontStyle = fontStyle),
        components = components,
        modifier = modifier.fillMaxWidth(),
        success = { state, resolvedComponents, successModifier ->
            Column(modifier = successModifier) {
                state.node.children.forEach { node ->
                    key(node.startOffset, node.endOffset, node.type) {
                        when {
                            node.type == GFMElementTypes.TABLE -> {
                                val table = remember(state.content, node.startOffset, node.endOffset) {
                                    parseMarkdownTable(state.content, node)
                                }
                                if (table == null) {
                                    MarkdownElement(node, resolvedComponents, state.content)
                                } else {
                                    WideContentHost(
                                        text = "",
                                        textStyle = LocalMarkdownTypography.current.text,
                                        horizontalChrome = 0.dp,
                                        minimumContentWidth = table.requiredWidth,
                                    ) {
                                        NexaraTableWidget(
                                            table = table,
                                            fontSize = fontSize,
                                            modifier = Modifier
                                                .padding(vertical = 8.dp)
                                                .fillMaxWidth(),
                                        )
                                    }
                                }
                            }

                            node.containsWideTableDescendant() -> {
                                val minimumWidth = remember(
                                    state.content,
                                    node.startOffset,
                                    node.endOffset,
                                ) {
                                    nestedTableMinimumWidth(state.content, node)
                                }
                                if (minimumWidth == null) {
                                    MarkdownElement(node, resolvedComponents, state.content)
                                } else {
                                    WideAwareContainer(
                                        node = node,
                                        components = resolvedComponents,
                                        content = state.content,
                                        minimumWidth = minimumWidth,
                                    )
                                }
                            }

                            node.type == MarkdownElementTypes.PARAGRAPH -> {
                                val standaloneCode = standaloneInlineCode(node, state.content)
                                if (standaloneCode == null) {
                                    MarkdownElement(node, resolvedComponents, state.content)
                                } else {
                                    val codeStyle = LocalMarkdownTypography.current.code.copy(
                                        color = textColor,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    WideContentHost(
                                        text = standaloneCode,
                                        textStyle = codeStyle,
                                        horizontalChrome = 16.dp,
                                    ) {
                                        Text(
                                            text = standaloneCode,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceContainer,
                                                    NexaraShapes.small,
                                                )
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = codeStyle,
                                            softWrap = false,
                                        )
                                    }
                                }
                            }

                            node.type == MarkdownElementTypes.CODE_FENCE -> MarkdownCodeFence(
                                content = state.content,
                                node = node,
                                style = LocalMarkdownTypography.current.code,
                            ) { code, language, style ->
                                WideContentHost(
                                    text = code,
                                    textStyle = style,
                                    horizontalChrome = 80.dp,
                                ) {
                                    CodeBlockWithHeader(
                                        code = code,
                                        language = language,
                                        fontSize = fontSize,
                                        onCodeChange = if (currentOnContentChange.value != null) {
                                            { newCode ->
                                                currentOnContentChange.value?.invoke(
                                                    replaceCodeInMarkdown(
                                                        currentMarkdown.value,
                                                        language,
                                                        code,
                                                        newCode,
                                                    ),
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    ) {
                                        MarkdownHighlightedCode(
                                            code = code,
                                            language = language,
                                            style = style,
                                        )
                                    }
                                }
                            }

                            node.type == MarkdownElementTypes.CODE_BLOCK -> MarkdownCodeBlock(
                                content = state.content,
                                node = node,
                                style = LocalMarkdownTypography.current.code,
                            ) { code, language, style ->
                                WideContentHost(
                                    text = code,
                                    textStyle = style,
                                    horizontalChrome = 80.dp,
                                ) {
                                    CodeBlockWithHeader(
                                        code = code,
                                        language = language,
                                        fontSize = fontSize,
                                        onCodeChange = if (currentOnContentChange.value != null) {
                                            { newCode ->
                                                currentOnContentChange.value?.invoke(
                                                    replaceCodeInMarkdown(
                                                        currentMarkdown.value,
                                                        language,
                                                        code,
                                                        newCode,
                                                    ),
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    ) {
                                        MarkdownHighlightedCode(
                                            code = code,
                                            language = language,
                                            style = style,
                                        )
                                    }
                                }
                            }

                            else -> MarkdownElement(node, resolvedComponents, state.content)
                        }
                    }
                }
            }
        },
    )
}

/**
 * 为含任意深度 GFM 表格的完整容器提供第一方外层横向视口；容器正文仍交给官方 renderer，
 * 因而引用条、列表标记、缩进、遍历语义与普通 Markdown 路径保持一致。
 */
@Composable
private fun WideAwareContainer(
    node: ASTNode,
    components: MarkdownComponents,
    content: String,
    minimumWidth: androidx.compose.ui.unit.Dp,
) {
    WideContentHost(
        text = "",
        textStyle = LocalMarkdownTypography.current.text,
        horizontalChrome = 0.dp,
        minimumContentWidth = minimumWidth,
    ) {
        MarkdownElement(node, components, content)
    }
}

/**
 * 横向手势只在第一方顶层宿主注册；内部宽内容始终获得有限的显式宽度，避免向第三方组件回调传播无限约束。
 */
@Composable
private fun WideContentHost(
    text: String,
    textStyle: TextStyle,
    horizontalChrome: androidx.compose.ui.unit.Dp,
    minimumContentWidth: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val viewportWidth = maxWidth
        val measuredTextWidthPx = remember(text, textStyle, density.density, density.fontScale) {
            widestLogicalLineWidthPx(text) { line ->
                textMeasurer.measure(
                    text = AnnotatedString(line),
                    style = textStyle,
                    softWrap = false,
                ).size.width
            }
        }
        val contentWidth = remember(
            measuredTextWidthPx,
            horizontalChrome,
            minimumContentWidth,
            density.density,
        ) {
            with(density) {
                maxOf(measuredTextWidthPx.toDp() + horizontalChrome, minimumContentWidth)
            }
        }
        val targetWidth = maxOf(viewportWidth, contentWidth)
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .testTag(UiTags.MARKDOWN_WIDE_CONTENT),
        ) {
            Box(modifier = Modifier.width(targetWidth)) {
                content()
            }
        }
    }
}

internal fun insertCjkSpacing(text: String): String {
    val protectedMap = mutableMapOf<String, String>()
    var counter = 0

    var processed = text.replace(Regex("`[^`]+`")) { match ->
        val key = "\u0000IC${counter++}\u0000"
        protectedMap[key] = match.value
        key
    }

    processed = processed.replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)")) { match ->
        val key = "\u0000LK${counter++}\u0000"
        protectedMap[key] = match.value
        key
    }

    val cjk = "\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaff\\u3000-\\u303f"
    processed = processed
        .replace(Regex("([$cjk])([a-zA-Z0-9])")) { "${it.groupValues[1]}\u200A${it.groupValues[2]}" }
        .replace(Regex("([a-zA-Z0-9])([$cjk])")) { "${it.groupValues[1]}\u200A${it.groupValues[2]}" }

    for ((key, value) in protectedMap) {
        processed = processed.replace(key, value)
    }

    return processed
}

internal fun normalizeLatexDelimiters(text: String): String {
    return text
        .replace(Regex("""\\\[(.*?)\\]""", RegexOption.DOT_MATCHES_ALL)) {
            "$$\n${it.groupValues[1].trim()}\n$$"
        }
        .replace(Regex("""\\\((.*?)\\\)""")) {
            "$${it.groupValues[1].trim()}$"
        }
}

internal fun sanitizeStreamingMarkdown(text: String): String {
    var result = text

    // Handle code block fences
    val fenceCount = Regex("""(?m)^```""").findAll(result).count()
    if (fenceCount % 2 != 0) {
        if (!result.endsWith("\n")) result += "\n"
        result += "```"
    }

    // Handle inline math $...$
    val inlineMathCount = Regex("""(?<!\$)\$(?!\$)""").findAll(result).count()
    if (inlineMathCount % 2 != 0) {
        if (result.endsWith("$")) {
            result = result.dropLast(1)
        }
    }

    // Handle block math $$...$$
    val blockMathCount = Regex("""\$\$""").findAll(result).count()
    if (blockMathCount % 2 != 0) {
        val lastIdx = result.lastIndexOf("$$")
        if (lastIdx >= 0) {
            result = result.substring(0, lastIdx)
        }
    }

    // Handle streaming bold spans such as "**预" before the closing marker arrives.
    val emphasisScan = Regex("""```[\s\S]*?```|`[^`]*`""").replace(result, "")
    val boldMarkerCount = Regex("""(?<!\*)\*\*(?!\*)""").findAll(emphasisScan).count()
    if (boldMarkerCount % 2 != 0) {
        result += "**"
    }

    return result
}

@Composable
private fun StreamingCursor() {
    val generatingDescription = stringResource(R.string.chat_cd_generating_response)
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
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
            .semantics {
                contentDescription = generatingDescription
            }
    )
}

internal fun slugify(text: String): String {
    return text.trim()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("[^\\p{L}\\p{N}-]"), "")
        .lowercase()
        .replace(Regex("-+"), "-")
        .trim('-')
        .takeIf { it.isNotEmpty() } ?: "heading"
}

private fun anchoredHeading(
    getStyle: (MarkdownComponentModel) -> TextStyle,
    contentChildType: IElementType,
): MarkdownComponent = { model ->
    val headingText = model.node.getUnescapedTextInNode(model.content)
    val slug = slugify(headingText)
    val headingDescription = stringResource(R.string.chat_cd_heading, headingText)
    MarkdownElementText(
        content = model.content,
        node = model.node,
        style = getStyle(model),
        contentChildType = contentChildType,
        modifier = Modifier
            .testTag("heading-$slug")
            .semantics {
                heading()
                contentDescription = headingDescription
            }
    )
}
