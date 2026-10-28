package com.promenar.infinity.data.generation

/** 按已知摘要原文替换实际摘要段，避免误匹配自定义指令或检索资料中的标签示例。 */
internal fun replaceHistorySummary(systemPrompt: String, summary: String?, previousSummary: String?): String {
    fun section(content: String, english: Boolean) = buildString {
        append("\n## History Summary\n")
        appendLine(if (english) "Earlier turns of this conversation were condensed into the summary below." else "较早的会话已总结为以下摘要。")
        appendLine("<history_summary>")
        appendLine(content.trim())
        appendLine("</history_summary>")
    }
    val replacement = summary?.takeIf(String::isNotBlank)?.let { section(it, english = false) }.orEmpty()
    val previous = previousSummary?.takeIf(String::isNotBlank)?.let { content ->
        listOf(section(content, english = true), section(content, english = false))
            .map { it to systemPrompt.lastIndexOf(it) }.filter { it.second >= 0 }.maxByOrNull { it.second }
    }
    return if (previous == null) systemPrompt + replacement
        else systemPrompt.replaceRange(previous.second, previous.second + previous.first.length, replacement)
}
