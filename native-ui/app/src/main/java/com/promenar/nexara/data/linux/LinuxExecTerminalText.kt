package com.promenar.nexara.data.linux

import com.promenar.nexara.data.exec.BuiltinCommandOutput

/** 有界的流式终端文本：保留未换行提示，过滤跨块 ANSI，回车覆盖当前行。 */
class LinuxExecTerminalText(private val limit: Int) {
    private val history = StringBuilder()
    private val line = StringBuilder()
    private var committed = 0L
    private var lineLength = 0L
    private var carriage = false
    private var escape = 0

    @Synchronized fun accept(text: String): String {
        val complete = StringBuilder()
        for (c in text) {
            if (escape != 0) {
                escape = when (escape) {
                    1 -> when (c) { '[' -> 2; ']', 'P', '_', '^' -> 3; in ' '..'/' -> 5; else -> 0 }
                    2 -> if (c in '@'..'~') 0 else 2
                    3 -> when(c) { '\u0007', '\u009c' -> 0; '\u001b' -> 4; else -> 3 }
                    4 -> if (c == '\\') 0 else 3
                    else -> if (c in '0'..'~') 0 else 5
                }
                continue
            }
            if (c == '\u001b') { escape = 1; continue }
            if (c == '\u009b') { escape = 2; continue }
            if (c == '\u009d') { escape = 3; continue }
            when (c) {
                '\r' -> carriage = true
                '\n' -> {
                    val value = line.toString() + "\n"
                    complete.append(value); history.append(value); trim(history)
                    committed += lineLength + 1; line.clear(); lineLength = 0; carriage = false
                }
                '\b' -> if (line.isNotEmpty()) { line.deleteCharAt(line.lastIndex); lineLength = (lineLength - 1).coerceAtLeast(0) }
                else -> if (c == '\t' || !c.isISOControl()) {
                    if (carriage) { line.clear(); lineLength = 0; carriage = false }
                    line.append(c); lineLength++; trim(line)
                }
            }
        }
        return complete.toString()
    }
    @Synchronized fun snapshot(): BuiltinCommandOutput {
        val text = (history.toString() + line).takeLast(limit).let { if (it.isNotEmpty() && it[0].isLowSurrogate()) it.drop(1) else it }
        return BuiltinCommandOutput(null, false, text, "", committed + lineLength, 0, committed + lineLength > text.length, false)
    }
    @Synchronized fun finish(): String = line.toString()
    private fun trim(text: StringBuilder) { if (text.length > limit) text.delete(0, text.length - limit) }
}
