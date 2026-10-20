package com.promenar.nexara.ui.common

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * 数学定界符不得跨入的字面量节点：代码与链接中的 `$`、`\(`、`\[` 只是普通字符。
 * 引用式链接不在此列，因为公式中的 `[0, 1]` 等区间写法会被解析成引用链接候选。
 */
private val mathLiteralTypes = setOf(
    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.CODE_BLOCK,
    MarkdownElementTypes.CODE_SPAN,
    MarkdownElementTypes.INLINE_LINK,
    MarkdownElementTypes.LINK_DEFINITION,
    MarkdownElementTypes.AUTOLINK,
    MarkdownElementTypes.IMAGE,
)

/** 由解析器确定代码与链接的边界，返回按起点排序的字面量区间；不含相关语法时不解析。 */
internal fun markdownMathLiteralRanges(text: String): List<IntRange> {
    val hasLiteralSyntax = text.any { it == '`' || it == '~' || it == '[' || it == '<' } ||
        text.lineSequence().any { markdownCodeIndent.containsMatchIn(it) }
    if (!hasLiteralSyntax) return emptyList()

    val ranges = mutableListOf<IntRange>()
    fun visit(node: ASTNode) {
        if (node.type in mathLiteralTypes) {
            if (node.endOffset > node.startOffset) ranges += node.startOffset until node.endOffset
        } else {
            node.children.forEach(::visit)
        }
    }
    visit(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text))
    return ranges
}

private fun MatchResult.overlaps(ranges: List<IntRange>): Boolean =
    ranges.any { it.first <= range.last && range.first <= it.last }

/**
 * 查找不与字面量区间重叠的匹配。候选与区间重叠时从其起点后一位继续查找，
 * 避免代码里的 `$` 吞掉紧随其后的真实公式（例如 `` `$a`$b$ ``）。
 */
internal fun Regex.findAllOutside(text: String, literalRanges: List<IntRange>): List<MatchResult> {
    if (literalRanges.isEmpty()) return findAll(text).toList()
    val matches = mutableListOf<MatchResult>()
    var start = 0
    while (start <= text.length) {
        val match = find(text, start) ?: break
        start = if (match.overlaps(literalRanges)) {
            match.range.first + 1
        } else {
            matches += match
            maxOf(match.range.last + 1, match.range.first + 1)
        }
    }
    return matches
}

/** 仅替换字面量区间之外的匹配，代码与链接原文保持不变。 */
internal fun Regex.replaceOutside(
    text: String,
    literalRanges: List<IntRange>,
    transform: (MatchResult) -> CharSequence,
): String {
    val matches = findAllOutside(text, literalRanges)
    if (matches.isEmpty()) return text
    return buildString {
        var cursor = 0
        for (match in matches) {
            append(text, cursor, match.range.first)
            append(transform(match))
            cursor = match.range.last + 1
        }
        append(text, cursor, text.length)
    }
}
