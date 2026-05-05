package com.promenar.nexara.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraTypography

private data class MarkdownBlock(
    val type: BlockType,
    val content: String,
    val language: String? = null
)

private sealed class BlockType {
    data class Heading(val level: Int) : BlockType()
    data object Paragraph : BlockType()
    data class CodeBlock(val language: String?) : BlockType()
    data class UnorderedList(val items: List<String>) : BlockType()
    data class OrderedList(val items: List<String>) : BlockType()
}

private fun parseMarkdown(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        when {
            line.startsWith("```") -> {
                val language = line.removePrefix("```").trim().ifBlank { null }
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(MarkdownBlock(BlockType.CodeBlock(language), codeLines.joinToString("\n")))
                i++
            }
            line.startsWith("### ") -> {
                blocks.add(MarkdownBlock(BlockType.Heading(3), line.removePrefix("### ")))
                i++
            }
            line.startsWith("## ") -> {
                blocks.add(MarkdownBlock(BlockType.Heading(2), line.removePrefix("## ")))
                i++
            }
            line.startsWith("# ") -> {
                blocks.add(MarkdownBlock(BlockType.Heading(1), line.removePrefix("# ")))
                i++
            }
            line.trim().startsWith("- ") || line.trim().startsWith("* ") -> {
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val trimmed = lines[i].trim()
                    if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                        items.add(trimmed.removePrefix("- ").removePrefix("* "))
                        i++
                    } else break
                }
                blocks.add(MarkdownBlock(BlockType.UnorderedList(items), ""))
            }
            line.trim().matches(Regex("^\\d+\\.\\s.*")) -> {
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val trimmed = lines[i].trim()
                    val match = Regex("^(\\d+)\\.\\s(.*)$").find(trimmed)
                    if (match != null) {
                        items.add(match.groupValues[2])
                        i++
                    } else break
                }
                blocks.add(MarkdownBlock(BlockType.OrderedList(items), ""))
            }
            line.isBlank() -> {
                i++
            }
            else -> {
                val paragraphLines = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank() &&
                    !lines[i].startsWith("#") &&
                    !lines[i].startsWith("```") &&
                    !lines[i].trim().startsWith("- ") &&
                    !lines[i].trim().startsWith("* ") &&
                    !lines[i].trim().matches(Regex("^\\d+\\.\\s.*"))
                ) {
                    paragraphLines.add(lines[i])
                    i++
                }
                if (paragraphLines.isNotEmpty()) {
                    blocks.add(MarkdownBlock(BlockType.Paragraph, paragraphLines.joinToString(" ")))
                }
            }
        }
    }
    return blocks
}

private fun buildInlineAnnotatedString(text: String): AnnotatedString {
    val combinedPattern = Regex("""(\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`|\[(.+?)]\((.+?)\))""")

    return buildAnnotatedString {
        var lastIndex = 0

        for (match in combinedPattern.findAll(text)) {
            if (match.range.first > lastIndex) {
                append(text.substring(lastIndex, match.range.first))
            }

            when {
                match.groupValues[2].isNotEmpty() -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(match.groupValues[2])
                    }
                }
                match.groupValues[3].isNotEmpty() -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(match.groupValues[3])
                    }
                }
                match.groupValues[4].isNotEmpty() -> {
                    withStyle(
                        SpanStyle(
                            background = NexaraColors.SurfaceHigh,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    ) {
                        append(match.groupValues[4])
                    }
                }
                match.groupValues[5].isNotEmpty() -> {
                    val label = match.groupValues[5]
                    val url = match.groupValues[6]
                    pushLink(LinkAnnotation.Url(url))
                    withStyle(
                        SpanStyle(
                            color = NexaraColors.Primary,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(label)
                    }
                    pop()
                }
            }
            lastIndex = match.range.last + 1
        }

        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (block in blocks) {
            when (block.type) {
                is BlockType.Heading -> {
                    val style = when (block.type.level) {
                        1 -> NexaraTypography.headlineLarge
                        2 -> NexaraTypography.headlineMedium
                        else -> NexaraTypography.headlineMedium.copy(fontSize = 18.sp)
                    }
                    Text(
                        text = buildInlineAnnotatedString(block.content),
                        style = style,
                        color = NexaraColors.OnSurface
                    )
                }
                is BlockType.Paragraph -> {
                    BasicText(
                        text = buildInlineAnnotatedString(block.content),
                        style = NexaraTypography.bodyMedium.copy(color = NexaraColors.OnSurface)
                    )
                }
                is BlockType.CodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(NexaraShapes.medium)
                            .background(NexaraColors.SurfaceLowest)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = block.content,
                            style = NexaraTypography.bodySmall,
                            color = NexaraColors.OnSurface,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                is BlockType.UnorderedList -> {
                    block.type.items.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "\u2022",
                                style = NexaraTypography.bodyMedium,
                                color = NexaraColors.Primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            BasicText(
                                text = buildInlineAnnotatedString(item),
                                style = NexaraTypography.bodyMedium.copy(color = NexaraColors.OnSurface),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                is BlockType.OrderedList -> {
                    block.type.items.forEachIndexed { idx, item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${idx + 1}.",
                                style = NexaraTypography.bodyMedium,
                                color = NexaraColors.Primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            BasicText(
                                text = buildInlineAnnotatedString(item),
                                style = NexaraTypography.bodyMedium.copy(color = NexaraColors.OnSurface),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        if (isStreaming) {
            StreamingCursor()
        }
    }
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
            .padding(start = 4.dp)
            .width(8.dp)
            .height(16.dp)
            .alpha(alpha)
            .background(NexaraColors.Primary, RoundedCornerShape(1.dp))
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun MarkdownTextPreview() {
    NexaraTheme {
        MarkdownText(
            markdown = """
                ## Hello World
                
                This is **bold** and *italic* and `inline code`.
                
                ### Features
                - Item one
                - Item two
                
                1. First
                2. Second
                
                ```kotlin
                fun main() {
                    println("Hello")
                }
                ```
                
                Check out [Nexara](https://nexara.ai) for more.
            """.trimIndent()
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun MarkdownTextStreamingPreview() {
    NexaraTheme {
        MarkdownText(
            markdown = "This is a streaming response with **bold** text...",
            isStreaming = true
        )
    }
}
