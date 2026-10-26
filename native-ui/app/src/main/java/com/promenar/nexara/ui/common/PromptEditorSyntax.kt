package com.promenar.nexara.ui.common

/** 编辑区轻量高亮的类别：标题整行、列表/引用/标题符号、加粗、行内与围栏代码。 */
internal enum class PromptSyntaxKind { HEADING, MARKER, STRONG, CODE }

internal data class PromptSyntaxSpan(val kind: PromptSyntaxKind, val start: Int, val end: Int)

private val HeadingPrefix = Regex("^#{1,6} +")
private val ListMarker = Regex("^[ \\t]*([-*+]|\\d+[.)])[ \\t]+")
private val QuoteMarker = Regex("^[ \\t]*(>)")
private val InlineCode = Regex("`[^`\\n]+`")
private val Strong = Regex("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*")

/**
 * 只识别写提示词时最常用、且在编辑态值得区分的 Markdown 片段；不做完整解析，预览仍交给 [MarkdownText]。
 * 围栏代码块内整行按代码处理，不再识别其中的标题、列表与加粗。
 */
internal fun promptSyntaxSpans(text: String): List<PromptSyntaxSpan> {
    val spans = mutableListOf<PromptSyntaxSpan>()
    var inFence = false
    var lineStart = 0
    while (lineStart <= text.length) {
        val newline = text.indexOf('\n', lineStart)
        val lineEnd = if (newline < 0) text.length else newline
        val line = text.substring(lineStart, lineEnd)
        val fence = line.trimStart().startsWith("```")
        if (inFence || fence) {
            if (line.isNotEmpty()) spans += PromptSyntaxSpan(PromptSyntaxKind.CODE, lineStart, lineEnd)
            if (fence) inFence = !inFence
        } else {
            HeadingPrefix.find(line)?.let { prefix ->
                spans += PromptSyntaxSpan(PromptSyntaxKind.HEADING, lineStart, lineEnd)
                spans += PromptSyntaxSpan(PromptSyntaxKind.MARKER, lineStart, lineStart + prefix.value.length)
            } ?: (ListMarker.find(line) ?: QuoteMarker.find(line))?.groups?.get(1)?.range?.let { marker ->
                spans += PromptSyntaxSpan(PromptSyntaxKind.MARKER, lineStart + marker.first, lineStart + marker.last + 1)
            }
            val code = InlineCode.findAll(line).map { it.range }.toList()
            Strong.findAll(line)
                .filter { strong -> code.none { it.first <= strong.range.last && strong.range.first <= it.last } }
                .forEach { strong ->
                    val start = lineStart + strong.range.first
                    val end = lineStart + strong.range.last + 1
                    spans += PromptSyntaxSpan(PromptSyntaxKind.STRONG, start, end)
                    spans += PromptSyntaxSpan(PromptSyntaxKind.MARKER, start, start + 2)
                    spans += PromptSyntaxSpan(PromptSyntaxKind.MARKER, end - 2, end)
                }
            code.forEach { spans += PromptSyntaxSpan(PromptSyntaxKind.CODE, lineStart + it.first, lineStart + it.last + 1) }
        }
        if (newline < 0) break
        lineStart = newline + 1
    }
    return spans.sortedWith(compareBy<PromptSyntaxSpan> { it.start }.thenBy { it.kind.ordinal })
}

/** 一次格式化动作：把原文 [start, end) 换成 [replacement]，换完后的选区为 [selectionStart, selectionEnd)。 */
internal data class PromptTextEdit(
    val start: Int,
    val end: Int,
    val replacement: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

private fun lineStartOf(text: String, offset: Int): Int = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(-1)).let { it + 1 }.coerceAtMost(offset)

private fun lineEndOf(text: String, offset: Int): Int = text.indexOf('\n', offset).let { if (it < 0) text.length else it }

/** 光标所在行的标题级别按 无 → # → ## → ### → 无 循环；光标原在符号内时落到新符号之后。 */
internal fun cycleHeading(text: String, selectionStart: Int, selectionEnd: Int): PromptTextEdit {
    val lineStart = lineStartOf(text, selectionStart)
    val oldPrefix = HeadingPrefix.find(text.substring(lineStart, lineEndOf(text, lineStart)))?.value.orEmpty()
    val level = oldPrefix.count { it == '#' }
    val newPrefix = if (level in 0..2) "#".repeat(level + 1) + " " else ""
    val delta = newPrefix.length - oldPrefix.length
    fun shift(offset: Int) = if (offset < lineStart + oldPrefix.length) lineStart + newPrefix.length else offset + delta
    return PromptTextEdit(lineStart, lineStart + oldPrefix.length, newPrefix, shift(selectionStart), shift(selectionEnd))
}

private val ListItemPrefix = Regex("^([ \\t]*)- ")

/** 选区覆盖的各行都已是“- ”列表时移除符号，否则给非空行加上；整块都是空行时也加。 */
internal fun toggleListItems(text: String, selectionStart: Int, selectionEnd: Int): PromptTextEdit {
    val blockStart = lineStartOf(text, selectionStart)
    val lastLineAnchor = if (selectionEnd > selectionStart) selectionEnd - 1 else selectionEnd
    val blockEnd = lineEndOf(text, lastLineAnchor.coerceAtLeast(blockStart))
    val lines = text.substring(blockStart, blockEnd).split('\n')
    val filled = lines.filter { it.isNotBlank() }
    val remove = filled.isNotEmpty() && filled.all { ListItemPrefix.containsMatchIn(it) }
    val changed = lines.map { line ->
        when {
            remove -> line.replaceFirst(ListItemPrefix, "$1")
            line.isNotBlank() || filled.isEmpty() -> line.replaceFirst(Regex("^([ \\t]*)"), "$1- ")
            else -> line
        }
    }
    val replacement = changed.joinToString("\n")
    return if (selectionStart == selectionEnd) {
        val caret = (selectionStart + replacement.length - (blockEnd - blockStart)).coerceAtLeast(blockStart)
        PromptTextEdit(blockStart, blockEnd, replacement, caret, caret)
    } else {
        PromptTextEdit(blockStart, blockEnd, replacement, blockStart, blockStart + replacement.length)
    }
}

/** 用成对标记包裹选区；选区本身或其两侧已带标记时取消。无选区时插入一对标记并把光标放在中间。 */
internal fun toggleInlineWrap(text: String, selectionStart: Int, selectionEnd: Int, marker: String): PromptTextEdit {
    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)
    val m = marker.length
    val selected = text.substring(start, end)
    val wrappedOutside = start >= m && end + m <= text.length &&
        text.regionMatches(start - m, marker, 0, m) && text.regionMatches(end, marker, 0, m)
    return when {
        selected.length >= 2 * m && selected.startsWith(marker) && selected.endsWith(marker) ->
            PromptTextEdit(start, end, selected.substring(m, selected.length - m), start, end - 2 * m)
        wrappedOutside ->
            PromptTextEdit(start - m, end + m, selected, start - m, end - m)
        else ->
            PromptTextEdit(start, end, marker + selected + marker, start + m, end + m)
    }
}

/** 编辑器的视图：宽窗口多一个左写右看的分栏。 */
internal enum class PromptEditorView { EDIT, SPLIT, PREVIEW }

internal fun promptEditorViews(wide: Boolean, previewAvailable: Boolean): List<PromptEditorView> = when {
    !previewAvailable -> listOf(PromptEditorView.EDIT)
    wide -> listOf(PromptEditorView.EDIT, PromptEditorView.SPLIT, PromptEditorView.PREVIEW)
    else -> listOf(PromptEditorView.EDIT, PromptEditorView.PREVIEW)
}

internal fun defaultPromptEditorView(wide: Boolean, previewAvailable: Boolean): PromptEditorView =
    if (wide && previewAvailable) PromptEditorView.SPLIT else PromptEditorView.EDIT

/** 折叠屏合上等窗口变窄的情况下，当前视图不可用时退回编辑。 */
internal fun PromptEditorView.coerceTo(views: List<PromptEditorView>): PromptEditorView =
    if (this in views) this else PromptEditorView.EDIT
