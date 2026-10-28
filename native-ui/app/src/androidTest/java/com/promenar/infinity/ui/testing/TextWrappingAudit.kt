package com.promenar.infinity.ui.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import java.text.BreakIterator

/** 一处末行孤字：文本、行数与末行内容。 */
data class OrphanLine(val text: String, val lineCount: Int, val lastLine: String)

/**
 * 末行孤字判定：自动折行产生的最后一行只剩一个可见字形（标点不计）。
 * 由显式换行符开始的末行属于作者意图，不判定为孤字。
 */
fun TextLayoutResult.orphanLastLine(): OrphanLine? {
    if (lineCount < 2) return null
    val text = layoutInput.text.text
    val last = lineCount - 1
    val start = getLineStart(last)
    if (start > 0 && text[start - 1] == '\n') return null
    val lastLine = text.substring(start, getLineEnd(last, visibleEnd = true)).trim()
    val glyphs = graphemes(lastLine).filterNot { grapheme -> grapheme.all(::isPunctuationOrSpace) }
    return if (glyphs.size <= 1) OrphanLine(text, lineCount, lastLine) else null
}

/** 收集当前组合中所有已布局文本的末行孤字。 */
fun ComposeContentTestRule.findOrphanLines(): List<OrphanLine> {
    val orphans = mutableListOf<OrphanLine>()
    fun visit(node: SemanticsNode) {
        if (SemanticsActions.GetTextLayoutResult in node.config) {
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            results.mapNotNullTo(orphans) { it.orphanLastLine() }
        }
        node.children.forEach(::visit)
    }
    onRoot(useUnmergedTree = true).fetchSemanticsNode().let(::visit)
    return orphans
}

private fun graphemes(value: String): List<String> {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(value)
    val result = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        result += value.substring(start, end)
        start = end
        end = iterator.next()
    }
    return result
}

private fun isPunctuationOrSpace(char: Char): Boolean = char.isWhitespace() || when (Character.getType(char)) {
    Character.CONNECTOR_PUNCTUATION.toInt(),
    Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
    -> true
    else -> false
}
