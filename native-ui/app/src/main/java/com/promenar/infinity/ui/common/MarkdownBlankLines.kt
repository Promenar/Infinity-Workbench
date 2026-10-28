package com.promenar.infinity.ui.common

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

private val markdownNewlineRun = Regex("(?:\\r\\n|\\n|\\r){3,}")
private val markdownNewline = Regex("\\r\\n|\\n|\\r")

/** 只折叠代码与链接之外已有的多余换行，不推断或补入缺失的 Markdown 边界。 */
internal fun collapseMarkdownBlankLines(text: String): String {
    if (!markdownNewlineRun.containsMatchIn(text)) return text
    val protected = BooleanArray(text.length)
    val opaque = BooleanArray(text.length)
    fun protect(start: Int, end: Int) {
        for (offset in start.coerceAtLeast(0) until end.coerceAtMost(text.length)) protected[offset] = true
    }
    val protectedTypes = setOf(
        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_BLOCK,
        MarkdownElementTypes.CODE_SPAN,
        MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK,
        MarkdownElementTypes.LINK_DEFINITION,
        MarkdownElementTypes.AUTOLINK,
        MarkdownElementTypes.IMAGE,
        GFMElementTypes.TABLE,
    )
    fun visit(node: ASTNode) {
        if (node.type in protectedTypes) {
            protect(node.startOffset, node.endOffset)
            if (node.type in setOf(MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK, MarkdownElementTypes.CODE_SPAN, GFMElementTypes.TABLE)) {
                for (offset in node.startOffset until node.endOffset.coerceAtMost(text.length)) opaque[offset] = true
            }
        } else node.children.forEach(::visit)
    }
    visit(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text))

    // 在流式尚未完整、或标签带空白行而未被解析成链接时，仍保留明确成对的原始输入。
    var cursor = 0
    while (cursor < text.length) {
        if (opaque[cursor] || text[cursor] == '\\') {
            cursor += if (text[cursor] == '\\') 2 else 1
            continue
        }
        if (text[cursor] == '`') {
            val width = text.substring(cursor).takeWhile { it == '`' }.length
            val delimiter = "`".repeat(width)
            var closing = text.indexOf(delimiter, cursor + width)
            while (closing >= 0 && (text.getOrNull(closing - 1) == '`' || text.getOrNull(closing + width) == '`')) {
                closing = text.indexOf(delimiter, closing + width)
            }
            if (closing >= 0) {
                protect(cursor, closing + width)
                cursor = closing + width
                continue
            }
        }
        if (text[cursor] == '[') {
            val labelEnd = matchingMarkdownDelimiter(text, cursor, '[', ']')
            if (labelEnd != null) {
                val destinationStart = labelEnd + 1
                val destinationEnd = when (text.getOrNull(destinationStart)) {
                    '(' -> matchingMarkdownDelimiter(text, destinationStart, '(', ')')
                    '[' -> matchingMarkdownDelimiter(text, destinationStart, '[', ']')
                    else -> null
                }
                if (destinationEnd != null) {
                    protect(cursor, destinationEnd + 1)
                    cursor = destinationEnd + 1
                    continue
                }
            }
        }
        cursor++
    }
    return markdownNewlineRun.replace(text) { match ->
        if (match.range.any { protected[it] }) match.value else markdownNewline.findAll(match.value).take(2).joinToString("") { it.value }
    }
}

private fun matchingMarkdownDelimiter(text: String, start: Int, open: Char, close: Char): Int? {
    var depth = 0
    var cursor = start
    while (cursor < text.length) {
        when (text[cursor]) {
            '\\' -> cursor++
            open -> depth++
            close -> if (--depth == 0) return cursor
        }
        cursor++
    }
    return null
}
