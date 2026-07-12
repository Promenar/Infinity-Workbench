package com.promenar.nexara.ui.common

import android.util.Log

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.LocalImageTransformer
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
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
import com.promenar.nexara.ui.renderer.parseGfmAlert
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

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
        ?: NexaraColors.OnBackground
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
                for (segment in mergedSegments) {
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
                                    compactSpacing = compactSpacing
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
    textColor: Color = NexaraColors.OnBackground,
    fontStyle: androidx.compose.ui.text.font.FontStyle? = null,
    compactSpacing: Boolean = false
) {
    var renderError by remember(content) { mutableStateOf(false) }
    // 使用 rememberUpdatedState 避免回调变化导致 components 重建
    val currentMarkdown = rememberUpdatedState(markdown)
    val currentOnContentChange = rememberUpdatedState(onContentChange)

    if (renderError) {
        Text(
            text = content,
            modifier = Modifier.fillMaxWidth(),
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
                    color = NexaraColors.OutlineVariant.copy(alpha = 0.5f)
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
            config = markdownAnnotatorConfig(eolAsNewLine = true)
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
        modifier = Modifier.fillMaxWidth()
    )
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
    MarkdownElementText(
        content = model.content,
        node = model.node,
        style = getStyle(model),
        contentChildType = contentChildType,
        modifier = Modifier
            .testTag("heading-$slug")
            .semantics {
                heading()
                contentDescription = "标题: $headingText"
            }
    )
}
