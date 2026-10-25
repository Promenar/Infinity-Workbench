package com.promenar.nexara.ui.linux.output

/** ANSI 颜色：0–15 为基本色与高亮色，16–255 为 xterm 扩展色，或 24 位真彩色。 */
sealed interface AnsiColor {
    data class Indexed(val index: Int) : AnsiColor
    data class Rgb(val red: Int, val green: Int, val blue: Int) : AnsiColor
}

data class AnsiStyle(
    val foreground: AnsiColor? = null,
    val background: AnsiColor? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
) {
    val isPlain: Boolean get() = this == PLAIN

    companion object {
        val PLAIN = AnsiStyle()
    }
}

/** [start] 含、[end] 不含，均为 [AnsiDocument.text] 中的 UTF-16 下标。 */
data class AnsiSpan(val start: Int, val end: Int, val style: AnsiStyle)

/** 去掉控制序列后的纯文本与样式区间；区间按位置排序、互不重叠，且不包含无样式区间。 */
data class AnsiDocument(val text: String, val spans: List<AnsiSpan>) {
    companion object {
        val EMPTY = AnsiDocument("", emptyList())
    }
}

/**
 * 把命令输出解析为纯文本与样式区间。
 *
 * 支持 SGR（`ESC [ … m`）的常用参数：重置、粗体、暗淡、斜体、下划线、反显，30–37/90–97 前景、40–47/100–107 背景、
 * 38/48 的 256 色与真彩色。其它 CSI 序列与 OSC 序列直接丢弃。单独的回车（进度条）回到行首覆盖本行；
 * 退格删除前一个字符。其余 C0 控制字符（制表符与换行除外）丢弃。
 */
object AnsiParser {
    private const val ESC = '\u001B'
    private const val BEL = '\u0007'

    fun parse(input: String): AnsiDocument {
        if (input.isEmpty()) return AnsiDocument.EMPTY
        val text = StringBuilder(input.length)
        val spans = ArrayList<AnsiSpan>()
        var style = AnsiStyle.PLAIN
        var runStart = 0
        var lineStart = 0

        fun closeRun() {
            if (!style.isPlain && text.length > runStart) spans += AnsiSpan(runStart, text.length, style)
            runStart = text.length
        }

        /** 删除 [from] 之后的文本，并裁剪受影响的样式区间。 */
        fun truncateTo(from: Int) {
            closeRun()
            text.setLength(from)
            while (spans.isNotEmpty() && spans.last().end > from) {
                val last = spans.removeAt(spans.lastIndex)
                if (last.start < from) spans += last.copy(end = from)
            }
            runStart = from
        }

        var index = 0
        while (index < input.length) {
            val char = input[index]
            when {
                char == ESC -> {
                    val next = input.getOrNull(index + 1)
                    when (next) {
                        '[' -> {
                            var cursor = index + 2
                            while (cursor < input.length && input[cursor].code in 0x30..0x3F) cursor++
                            val parameterEnd = cursor
                            while (cursor < input.length && input[cursor].code in 0x20..0x2F) cursor++
                            if (cursor >= input.length) {
                                index = input.length
                                continue
                            }
                            val final = input[cursor]
                            if (final == 'm' && parameterEnd == cursor) {
                                val updated = applySgr(style, input.substring(index + 2, parameterEnd))
                                if (updated != style) {
                                    closeRun()
                                    style = updated
                                }
                            }
                            index = cursor + 1
                        }
                        ']' -> {
                            var cursor = index + 2
                            while (cursor < input.length) {
                                if (input[cursor] == BEL) { cursor++; break }
                                if (input[cursor] == ESC && input.getOrNull(cursor + 1) == '\\') { cursor += 2; break }
                                cursor++
                            }
                            index = cursor
                        }
                        null -> index++
                        // 字符集指定（如 ESC ( B）带一个参数字符。
                        '(', ')', '*', '+' -> index += 3
                        else -> index += 2
                    }
                }
                char == '\r' -> {
                    if (input.getOrNull(index + 1) == '\n') {
                        index++
                    } else {
                        // 进度条：下一段输出覆盖本行，这里直接从行首重写。
                        truncateTo(lineStart)
                        index++
                    }
                }
                char == '\n' -> {
                    text.append('\n')
                    lineStart = text.length
                    index++
                }
                char == '\b' -> {
                    if (text.length > lineStart) truncateTo(text.length - 1)
                    index++
                }
                char == '\t' -> {
                    text.append('\t')
                    index++
                }
                char.code < 0x20 || char.code == 0x7F -> index++
                else -> {
                    text.append(char)
                    index++
                }
            }
        }
        closeRun()
        return AnsiDocument(text.toString(), spans)
    }

    /** 只去掉控制序列，用于复制、保存与搜索。 */
    fun strip(input: String): String = parse(input).text

    internal fun applySgr(current: AnsiStyle, parameters: String): AnsiStyle {
        val codes = if (parameters.isEmpty()) listOf(0) else parameters.split(';', ':').map { it.toIntOrNull() ?: 0 }
        var style = current
        var index = 0
        while (index < codes.size) {
            when (val code = codes[index]) {
                0 -> style = AnsiStyle.PLAIN
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(dim = true)
                3 -> style = style.copy(italic = true)
                4 -> style = style.copy(underline = true)
                7 -> style = style.copy(inverse = true)
                21, 22 -> style = style.copy(bold = false, dim = false)
                23 -> style = style.copy(italic = false)
                24 -> style = style.copy(underline = false)
                27 -> style = style.copy(inverse = false)
                in 30..37 -> style = style.copy(foreground = AnsiColor.Indexed(code - 30))
                39 -> style = style.copy(foreground = null)
                in 40..47 -> style = style.copy(background = AnsiColor.Indexed(code - 40))
                49 -> style = style.copy(background = null)
                in 90..97 -> style = style.copy(foreground = AnsiColor.Indexed(code - 90 + 8))
                in 100..107 -> style = style.copy(background = AnsiColor.Indexed(code - 100 + 8))
                38, 48 -> {
                    val (color, consumed) = extendedColor(codes, index + 1)
                    if (color != null) {
                        style = if (code == 38) style.copy(foreground = color) else style.copy(background = color)
                    }
                    index += consumed
                }
                else -> Unit
            }
            index++
        }
        return style
    }

    /** 返回颜色与额外消耗的参数个数。 */
    private fun extendedColor(codes: List<Int>, from: Int): Pair<AnsiColor?, Int> = when (codes.getOrNull(from)) {
        5 -> {
            val value = codes.getOrNull(from + 1)
            (value?.takeIf { it in 0..255 }?.let(AnsiColor::Indexed)) to 2
        }
        2 -> {
            val red = codes.getOrNull(from + 1)
            val green = codes.getOrNull(from + 2)
            val blue = codes.getOrNull(from + 3)
            if (red != null && green != null && blue != null) {
                AnsiColor.Rgb(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255)) to 4
            } else null to (codes.size - from)
        }
        else -> null to 0
    }
}

/** 把多份文档按顺序拼接，样式区间整体平移。 */
fun concatAnsi(parts: List<AnsiDocument>): AnsiDocument {
    if (parts.size == 1) return parts.single()
    val text = StringBuilder()
    val spans = ArrayList<AnsiSpan>()
    parts.forEach { part ->
        val offset = text.length
        text.append(part.text)
        part.spans.mapTo(spans) { it.copy(start = it.start + offset, end = it.end + offset) }
    }
    return AnsiDocument(text.toString(), spans)
}
